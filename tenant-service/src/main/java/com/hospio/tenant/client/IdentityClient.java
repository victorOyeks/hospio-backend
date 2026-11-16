package com.hospio.tenant.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.delivery.Delivery;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Creates the owner's login in identity-service at handover, or re-issues it when the handover is sent again,
 * and resolves admin names (account managers, assignees) through the internal contacts lookup, cached briefly.
 */
@Slf4j
@Component
public class IdentityClient {

    private static final Duration NAME_CACHE = Duration.ofMinutes(5);
    private static final TypeReference<ApiResponse<UserCreated>> USER_CREATED = new TypeReference<>() {
    };
    private static final TypeReference<ApiResponse<UserDeliveries>> USER_DELIVERIES = new TypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<Taken>> TAKEN = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<List<Contact>>> CONTACTS = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final RestClient systemClient;
    private final ObjectMapper objectMapper;
    private final Map<String, CachedName> names = new ConcurrentHashMap<>();

    public IdentityClient(@Value("${services.identity.url}") String identityUrl, JwtUtil jwtUtil,
                          @Value("${services.internal.api-key}") String internalApiKey, ObjectMapper objectMapper) {
        this.restClient = ServiceClients.internal(identityUrl, jwtUtil, internalApiKey);
        this.systemClient = ServiceClients.system(identityUrl, jwtUtil, internalApiKey);
        this.objectMapper = objectMapper;
    }

    /** The login identity created, with the deliveries identity already published (passed through to the caller). */
    public record CreatedLogin(String userId, String temporaryPassword, Instant expiresAt, String inviteToken, Instant inviteExpiresAt,
                               List<Delivery> deliveries) {
    }

    private record CachedName(String name, Instant until) {
    }

    /** identity's UserCreatedResponse, without its deliveries (read on their own so an unreadable one never fails the login). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserCreated(String userId, String temporaryPassword, Instant temporaryPasswordExpiresAt, String inviteToken,
                       Instant inviteExpiresAt) {
    }

    /** The deliveries identity echoed with a created or re-issued login. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserDeliveries(List<Delivery> deliveries) {
    }

    /** The fields tenant reads of identity's UserContactResponse. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Contact(String id, String fullName) {
    }

    /** A new login in {@code businessId}; business-wide when {@code hotelId} is null (the owner). */
    public CreatedLogin createUser(String fullName, String email, String phone, String role, String hotelId, String businessId) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fullName", fullName);
            body.put("email", email);
            body.put("phone", phone == null ? "" : phone);
            body.put("role", role);
            body.put("level", "FULL");
            body.put("businessId", businessId);
            if (hotelId != null) {
                body.put("hotelId", hotelId);
            }
            var request = restClient.post().uri("/internal/users");
            if (hotelId != null) {
                request = request.header("X-Hotel-Id", hotelId);
            }
            return parse(request.body(body).retrieve().body(String.class));
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not create login in identity-service", e);
            throw new ExternalServiceException("Could not create the login right now", e);
        }
    }

    public CreatedLogin resendInvite(String userId) {
        try {
            return parse(restClient.post().uri("/internal/users/{id}/resend-invite", userId).retrieve().body(String.class));
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not re-issue login {} in identity-service", userId, e);
            throw new ExternalServiceException("Could not re-issue the login right now", e);
        }
    }

    /** What identity already has: whether a login signs in with this email, or uses this phone. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Taken(boolean email, boolean phone) {
    }

    /**
     * Asks identity whether a login already uses this email or phone. When identity cannot answer, nothing is refused here
     * (tenant's own checks still run and the handover refuses a taken sign-in).
     */
    public Taken taken(String email, String phone) {
        if (email == null && phone == null) {
            return new Taken(false, false);
        }
        try {
            ApiResponse<Taken> body = systemClient.get().uri(u -> {
                u.path("/internal/users/taken");
                if (email != null) {
                    u.queryParam("email", email);
                }
                if (phone != null) {
                    u.queryParam("phone", phone.replaceAll("[^0-9]", ""));
                }
                return u.build();
            }).retrieve().body(TAKEN);
            return body == null || body.data() == null ? new Taken(false, false) : body.data();
        } catch (Exception e) {
            log.warn("Could not ask identity-service whether {} / {} are taken: {}", email, phone, e.getMessage());
            return new Taken(false, false);
        }
    }

    /**
     * Full names for user ids (account managers, assignees), from a 5-minute cache and one contacts call for
     * the rest. Ids identity does not know, or cannot answer for right now, are left out: a missing name never
     * fails a listing.
     */
    public Map<String, String> names(Collection<String> userIds) {
        Map<String, String> out = new HashMap<>();
        List<String> missing = new ArrayList<>();
        Instant now = Instant.now();
        for (String id : userIds) {
            if (id == null) {
                continue;
            }
            CachedName cached = names.get(id);
            if (cached != null && cached.until().isAfter(now)) {
                out.put(id, cached.name());
            } else if (!missing.contains(id)) {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            return out;
        }
        try {
            ApiResponse<List<Contact>> body = systemClient.get()
                    .uri(u -> u.path("/internal/users/contacts").queryParam("userIds", missing.toArray()).build())
                    .retrieve().body(CONTACTS);
            if (body != null && body.data() != null) {
                for (Contact c : body.data()) {
                    String id = c == null ? null : c.id();
                    String name = c == null ? null : c.fullName();
                    if (id != null && name != null) {
                        out.put(id, name);
                        names.put(id, new CachedName(name, now.plus(NAME_CACHE)));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not resolve {} user names from identity-service: {}", missing.size(), e.getMessage());
        }
        return out;
    }

    CreatedLogin parse(String json) throws java.io.IOException {
        ApiResponse<UserCreated> body = json == null || json.isBlank() ? null : objectMapper.readValue(json, USER_CREATED);
        UserCreated data = body == null ? null : body.data();
        if (data == null) {
            throw new ExternalServiceException("identity-service returned no user");
        }
        List<Delivery> deliveries = List.of();
        try {
            ApiResponse<UserDeliveries> echoed = objectMapper.readValue(json, USER_DELIVERIES);
            if (echoed != null && echoed.data() != null && echoed.data().deliveries() != null) {
                deliveries = echoed.data().deliveries();
            }
        } catch (java.io.IOException e) {
            log.warn("Could not read deliveries from identity-service: {}", e.getMessage());
        }
        return new CreatedLogin(data.userId() == null ? "" : data.userId(), data.temporaryPassword() == null ? "" : data.temporaryPassword(),
                data.temporaryPasswordExpiresAt(), data.inviteToken(), data.inviteExpiresAt(), deliveries);
    }
}

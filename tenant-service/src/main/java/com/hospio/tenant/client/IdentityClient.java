package com.hospio.tenant.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.delivery.Delivery;
import com.hospio.common.exception.ExternalServiceException;
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
            return parse(request.body(body).retrieve().body(JsonNode.class));
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not create login in identity-service", e);
            throw new ExternalServiceException("Could not create the login right now", e);
        }
    }

    public CreatedLogin resendInvite(String userId) {
        try {
            return parse(restClient.post().uri("/internal/users/{id}/resend-invite", userId).retrieve().body(JsonNode.class));
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not re-issue login {} in identity-service", userId, e);
            throw new ExternalServiceException("Could not re-issue the login right now", e);
        }
    }

    /** What identity already has: whether a login signs in with this email, or uses this phone. */
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
            JsonNode body = systemClient.get().uri(u -> {
                u.path("/internal/users/taken");
                if (email != null) {
                    u.queryParam("email", email);
                }
                if (phone != null) {
                    u.queryParam("phone", phone.replaceAll("[^0-9]", ""));
                }
                return u.build();
            }).retrieve().body(JsonNode.class);
            JsonNode data = body == null ? null : body.path("data");
            return data == null ? new Taken(false, false) : new Taken(data.path("email").asBoolean(false), data.path("phone").asBoolean(false));
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
            JsonNode body = systemClient.get()
                    .uri(u -> u.path("/internal/users/contacts").queryParam("userIds", missing.toArray()).build())
                    .retrieve().body(JsonNode.class);
            if (body != null) {
                for (JsonNode c : body.path("data")) {
                    String id = c.path("id").asText(null);
                    String name = c.path("fullName").asText(null);
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

    private CreatedLogin parse(JsonNode body) {
        JsonNode data = body == null ? null : body.path("data");
        if (data == null || data.isMissingNode() || data.isNull()) {
            throw new ExternalServiceException("identity-service returned no user");
        }
        List<Delivery> deliveries = List.of();
        JsonNode d = data.path("deliveries");
        if (d.isArray() && !d.isEmpty()) {
            try {
                deliveries = objectMapper.convertValue(d, new TypeReference<List<Delivery>>() { });
            } catch (IllegalArgumentException e) {
                log.warn("Could not read deliveries from identity-service: {}", e.getMessage());
            }
        }
        return new CreatedLogin(data.path("userId").asText(), data.path("temporaryPassword").asText(),
                instant(data.path("temporaryPasswordExpiresAt")), data.path("inviteToken").asText(null), instant(data.path("inviteExpiresAt")),
                deliveries);
    }

    private static Instant instant(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : Instant.parse(node.asText());
    }
}

package com.hospio.platform.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.delivery.Delivery;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** People by id (names for rows, the ticket opener's email) and the marketer login's creation. */
@Slf4j
@Component
public class IdentityClient {

    private final RestClient restClient;

    public IdentityClient(@Value("${services.identity.url}") String identityUrl, JwtUtil jwtUtil,
                          @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(identityUrl, jwtUtil, internalApiKey);
    }

    private static final ParameterizedTypeReference<ApiResponse<Contact>> CONTACT = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<List<Contact>>> CONTACTS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<UserCreated>> CREATED = new ParameterizedTypeReference<>() {
    };

    /** identity's UserContactResponse, the fields platform uses; no roles read as none. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contact(String id, String fullName, String email, String phone, String businessId, String status, List<String> roles) {
        public Contact {
            roles = roles == null ? List.of() : roles;
        }
    }

    /** identity's UserCreatedResponse; {@code deliveries} null when identity's response had no such field. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserCreated(String userId, String temporaryPassword, Instant temporaryPasswordExpiresAt, String inviteToken, Instant inviteExpiresAt,
                       List<EchoedDelivery> deliveries) {
    }

    /**
     * One echoed delivery as identity sent it. Channel and kind stay text here so one this build doesn't know is skipped on its
     * own (the message is still published by identity) instead of failing the login.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EchoedDelivery(String channel, String to, String subject, String text, String kind, String ref, String status) {
        Delivery toDelivery() {
            return new Delivery(Delivery.Channel.valueOf(channel == null ? "EMAIL" : channel), to, subject, text,
                    Delivery.Kind.valueOf(kind == null ? "INVITE" : kind), ref, status, null, null);
        }
    }

    /** {@code deliveries} is null when identity's response had no such field (an older build), empty when echo is off. */
    public record CreatedLogin(String userId, String temporaryPassword, Instant temporaryPasswordExpiresAt, String inviteToken, Instant inviteExpiresAt,
                               List<Delivery> deliveries) {
    }

    /** GET /internal/users/contacts?userIds= (exists). Empty on failure. */
    public List<Contact> contacts(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        try {
            List<Contact> d = TenantClient.data(restClient.get().uri(b -> b.path("/internal/users/contacts").queryParam("userIds", String.join(",", userIds)).build())
                    .retrieve().body(CONTACTS));
            return d == null ? new ArrayList<>() : new ArrayList<>(d);
        } catch (Exception e) {
            log.warn("identity-service contacts lookup failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** GET /internal/users/{id}/contact (exists). */
    public Optional<Contact> contact(String userId) {
        if (userId == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(TenantClient.data(restClient.get().uri("/internal/users/{id}/contact", userId).retrieve().body(CONTACT)));
        } catch (Exception e) {
            log.warn("identity-service contact {} failed: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    /** identity's own sentence from a 409 body ("This phone number is already in use on another account"), else the usual one. */
    /** The manager (or owner) who typed their PIN (password) on a colleague's screen to approve something. */
    public record Approver(String userId, String fullName, boolean owner) {
    }

    private static final ParameterizedTypeReference<ApiResponse<Approver>> APPROVER = new ParameterizedTypeReference<>() {
    };

    /**
     * POST /internal/users/verify-approver. Identity's refusals (unknown person, not a manager here, wrong or locked PIN) come back
     * as the same sentence for the screen: 401 as {@code UnauthorizedException} would log the caller out, so they are 400s here.
     */
    public Approver verifyApprover(String identifier, String secret, String businessId, String hotelId, boolean ownerOnly) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("identifier", identifier);
        body.put("secret", secret);
        body.put("businessId", businessId);
        body.put("hotelId", hotelId);
        body.put("ownerOnly", ownerOnly);
        try {
            Approver a = TenantClient.data(restClient.post().uri("/internal/users/verify-approver").body(body).retrieve().body(APPROVER));
            if (a == null || a.userId() == null) {
                throw new com.hospio.common.exception.BadRequestException("Could not check that PIN. Try again");
            }
            return a;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            String m = "";
            try {
                m = new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.getResponseBodyAsString()).path("message").asText("");
            } catch (Exception ignored) {
                // not JSON
            }
            throw new com.hospio.common.exception.BadRequestException(m.isBlank() ? "Could not check that PIN. Try again" : m);
        }
    }

    public static String conflictMessage(String body) {
        try {
            String m = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("message").asText("");
            if (!m.isBlank() && m.length() <= 200) {
                return m;
            }
        } catch (Exception ignored) {
            // not JSON: the usual sentence
        }
        return "A login with that email already exists";
    }

    /** GET /internal/users/platform?role=: active holders of a platform role (SUPER_ADMIN, SUPPORT, FINANCE_ADMIN). Empty on failure. */
    public List<Contact> platformStaff(String role) {
        try {
            List<Contact> d = TenantClient.data(restClient.get().uri(b -> b.path("/internal/users/platform").queryParam("role", role).build())
                    .retrieve().body(CONTACTS));
            return d == null ? new ArrayList<>() : new ArrayList<>(d);
        } catch (Exception e) {
            log.warn("identity-service platform staff ({}) lookup failed: {}", role, e.getMessage());
            return List.of();
        }
    }

    /** POST /internal/users with role MARKETER and no business: the marketer portal login. Throws when it cannot be created. */
    public CreatedLogin createMarketer(String fullName, String email, String phone) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fullName", fullName);
            body.put("email", email);
            body.put("phone", phone == null ? "" : phone);
            body.put("role", "MARKETER");
            body.put("level", "FULL");
            UserCreated d = TenantClient.data(restClient.post().uri("/internal/users").body(body).retrieve().body(CREATED));
            if (d == null) {
                throw new ExternalServiceException("identity-service returned no user");
            }
            List<Delivery> deliveries = null;
            if (d.deliveries() != null) {
                deliveries = new ArrayList<>();
                for (EchoedDelivery n : d.deliveries()) {
                    try {
                        deliveries.add(n.toDelivery());
                    } catch (IllegalArgumentException ignored) {
                        // a channel or kind this build does not know; the message is still published by identity
                    }
                }
            }
            return new CreatedLogin(d.userId() == null ? "" : d.userId(), d.temporaryPassword(), d.temporaryPasswordExpiresAt(),
                    d.inviteToken(), d.inviteExpiresAt(), deliveries);
        } catch (ExternalServiceException e) {
            throw e;
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) {
            // identity says which it is: the email, or the phone number on another account.
            throw new com.hospio.common.exception.ConflictException(conflictMessage(e.getResponseBodyAsString()));
        } catch (Exception e) {
            log.error("Could not create the marketer login in identity-service", e);
            throw new ExternalServiceException("Could not create the login right now", e);
        }
    }
}

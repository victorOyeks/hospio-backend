package com.hospio.platform.client;

import com.fasterxml.jackson.databind.JsonNode;
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

    public record Contact(String id, String fullName, String email, String phone, String businessId, String status, List<String> roles) {
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
            JsonNode body = restClient.get().uri(b -> b.path("/internal/users/contacts").queryParam("userIds", String.join(",", userIds)).build())
                    .retrieve().body(JsonNode.class);
            JsonNode d = TenantClient.data(body);
            List<Contact> out = new ArrayList<>();
            if (d != null && d.isArray()) {
                d.forEach(n -> out.add(contact(n)));
            }
            return out;
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
            JsonNode body = restClient.get().uri("/internal/users/{id}/contact", userId).retrieve().body(JsonNode.class);
            JsonNode d = TenantClient.data(body);
            return d == null ? Optional.empty() : Optional.of(contact(d));
        } catch (Exception e) {
            log.warn("identity-service contact {} failed: {}", userId, e.getMessage());
            return Optional.empty();
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
            JsonNode response = restClient.post().uri("/internal/users").body(body).retrieve().body(JsonNode.class);
            JsonNode d = TenantClient.data(response);
            if (d == null) {
                throw new ExternalServiceException("identity-service returned no user");
            }
            List<Delivery> deliveries = null;
            if (d.has("deliveries") && d.path("deliveries").isArray()) {
                deliveries = new ArrayList<>();
                for (JsonNode n : d.path("deliveries")) {
                    try {
                        deliveries.add(new Delivery(Delivery.Channel.valueOf(n.path("channel").asText("EMAIL")), n.path("to").asText(null),
                                n.path("subject").asText(null), n.path("text").asText(null), Delivery.Kind.valueOf(n.path("kind").asText("INVITE")),
                                n.path("ref").asText(null), n.path("status").asText(null), null, null));
                    } catch (IllegalArgumentException ignored) {
                        // a channel or kind this build does not know; the message is still published by identity
                    }
                }
            }
            return new CreatedLogin(d.path("userId").asText(), d.path("temporaryPassword").asText(null), TenantClient.instant(d.path("temporaryPasswordExpiresAt")),
                    d.path("inviteToken").asText(null), TenantClient.instant(d.path("inviteExpiresAt")), deliveries);
        } catch (ExternalServiceException e) {
            throw e;
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) {
            throw new com.hospio.common.exception.ConflictException("A login with that email already exists");
        } catch (Exception e) {
            log.error("Could not create the marketer login in identity-service", e);
            throw new ExternalServiceException("Could not create the login right now", e);
        }
    }

    private static Contact contact(JsonNode n) {
        List<String> roles = new ArrayList<>();
        if (n.path("roles").isArray()) {
            n.path("roles").forEach(r -> roles.add(r.asText()));
        }
        return new Contact(n.path("id").asText(null), n.path("fullName").asText(null), n.path("email").asText(null), n.path("phone").asText(null),
                n.path("businessId").asText(null), n.path("status").asText(null), roles);
    }
}

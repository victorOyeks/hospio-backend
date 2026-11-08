package com.hospio.tenant.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Creates the owner's login in identity-service at handover, or re-issues it when the handover is sent again. */
@Slf4j
@Component
public class IdentityClient {

    private final RestClient restClient;

    public IdentityClient(@Value("${services.identity.url}") String identityUrl, JwtUtil jwtUtil,
                          @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(identityUrl, jwtUtil, internalApiKey);
    }

    public record CreatedLogin(String userId, String temporaryPassword, Instant expiresAt, String inviteToken, Instant inviteExpiresAt) {
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

    private static CreatedLogin parse(JsonNode body) {
        JsonNode data = body == null ? null : body.path("data");
        if (data == null || data.isMissingNode() || data.isNull()) {
            throw new ExternalServiceException("identity-service returned no user");
        }
        return new CreatedLogin(data.path("userId").asText(), data.path("temporaryPassword").asText(),
                instant(data.path("temporaryPasswordExpiresAt")), data.path("inviteToken").asText(null), instant(data.path("inviteExpiresAt")));
    }

    private static Instant instant(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : Instant.parse(node.asText());
    }
}

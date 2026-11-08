package com.hospio.tenant.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Creates the owner's and first manager's logins in identity-service during onboarding. */
@Slf4j
@Component
public class IdentityClient {

    private final RestClient restClient;

    public IdentityClient(@Value("${services.identity.url}") String identityUrl, JwtUtil jwtUtil,
                          @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(identityUrl, jwtUtil, internalApiKey);
    }

    public record CreatedLogin(String userId, String temporaryPassword, Instant expiresAt) {
    }

    public CreatedLogin createUser(String fullName, String email, String phone, String role, String hotelId, String businessId) {
        try {
            JsonNode body = restClient.post()
                    .uri("/internal/users")
                    .header("X-Hotel-Id", hotelId == null ? "" : hotelId)
                    .body(Map.of("fullName", fullName, "email", email, "phone", phone == null ? "" : phone,
                            "role", role, "level", "FULL", "hotelId", hotelId == null ? "" : hotelId))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode data = body == null ? null : body.path("data");
            if (data == null || data.isMissingNode()) {
                throw new ExternalServiceException("identity-service returned no user");
            }
            return new CreatedLogin(data.path("userId").asText(), data.path("temporaryPassword").asText(),
                    Instant.parse(data.path("temporaryPasswordExpiresAt").asText()));
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not create login in identity-service", e);
            throw new ExternalServiceException("Could not create the login right now", e);
        }
    }
}

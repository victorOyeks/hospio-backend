package com.hospio.hotelconfig.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Which business a hotel belongs to (so platform callers can write its settings), and setup templates by id. */
@Slf4j
@Component
public class TenantClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TenantClient(@Value("${services.tenant.url}") String tenantUrl, JwtUtil jwtUtil,
                        @Value("${services.internal.api-key}") String internalApiKey, ObjectMapper objectMapper) {
        this.restClient = ServiceClients.internal(tenantUrl, jwtUtil, internalApiKey);
        this.objectMapper = objectMapper;
    }

    public Optional<String> businessOf(String hotelId) {
        try {
            JsonNode body = restClient.get().uri("/internal/hotels/{id}", hotelId).retrieve().body(JsonNode.class);
            String businessId = body == null ? null : body.path("data").path("businessId").asText(null);
            return Optional.ofNullable(businessId);
        } catch (Exception e) {
            log.warn("Could not read hotel {}: {}", hotelId, e.getMessage());
            return Optional.empty();
        }
    }

    /** The template's settings JSON, parsed; empty when unknown or unreachable. */
    public Optional<JsonNode> template(String templateId) {
        try {
            JsonNode body = restClient.get().uri("/internal/setup-templates/{id}", templateId).retrieve().body(JsonNode.class);
            JsonNode settings = body == null ? null : body.path("data").path("settings");
            if (settings == null || settings.isMissingNode() || settings.isNull()) {
                return Optional.empty();
            }
            return Optional.of(settings.isTextual() ? objectMapper.readTree(settings.asText()) : settings);
        } catch (Exception e) {
            log.warn("Could not read setup template {}: {}", templateId, e.getMessage());
            return Optional.empty();
        }
    }
}

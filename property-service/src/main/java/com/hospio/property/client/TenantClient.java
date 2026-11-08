package com.hospio.property.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Keeps the hotel's room count in tenant-service current (best effort: a failure never blocks a room
 * change) and tells platform callers which business a hotel belongs to.
 */
@Slf4j
@Component
public class TenantClient {

    private final RestClient restClient;

    public TenantClient(@Value("${services.tenant.url}") String tenantUrl, JwtUtil jwtUtil,
                        @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(tenantUrl, jwtUtil, internalApiKey);
    }

    public void updateRoomCount(String hotelId, long count) {
        try {
            restClient.put().uri("/internal/hotels/{id}/room-count?count={c}", hotelId, count).retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("Could not update room count for hotel {}: {}", hotelId, e.getMessage());
        }
    }

    public Optional<String> businessOf(String hotelId) {
        try {
            JsonNode body = restClient.get().uri("/internal/hotels/{id}", hotelId).retrieve().body(JsonNode.class);
            return Optional.ofNullable(body == null ? null : body.path("data").path("businessId").asText(null));
        } catch (Exception e) {
            log.warn("Could not read hotel {}: {}", hotelId, e.getMessage());
            return Optional.empty();
        }
    }
}

package com.hospio.property.client;

import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Keeps the hotel's room count in tenant-service current. Best effort: a failure never blocks a room change. */
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
}

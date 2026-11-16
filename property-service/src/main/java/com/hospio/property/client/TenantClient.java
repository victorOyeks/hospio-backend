package com.hospio.property.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Keeps the hotel's room count in tenant-service current (best effort: a failure never blocks a room
 * change) and tells platform callers which business a hotel belongs to.
 */
@Slf4j
@Component
public class TenantClient {

    /** A hotel as tenant-service's GET /internal/hotels/{id} gives it (HotelResponse), the field read here. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hotel(String businessId) {
    }

    private static final ParameterizedTypeReference<ApiResponse<Hotel>> HOTEL = new ParameterizedTypeReference<>() {
    };

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
            ApiResponse<Hotel> body = restClient.get().uri("/internal/hotels/{id}", hotelId).retrieve().body(HOTEL);
            return Optional.ofNullable(body == null || body.data() == null ? null : body.data().businessId());
        } catch (Exception e) {
            log.warn("Could not read hotel {}: {}", hotelId, e.getMessage());
            return Optional.empty();
        }
    }
}

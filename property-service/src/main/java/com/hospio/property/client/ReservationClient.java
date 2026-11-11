package com.hospio.property.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Asks reservation-service whether a room has bookings before it is deleted. */
@Slf4j
@Component
public class ReservationClient {

    private final RestClient restClient;

    public ReservationClient(@Value("${services.reservation.url}") String url, JwtUtil jwtUtil,
                             @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
    }

    /** Bookings the room has had in any status. Fails (so nothing is deleted) when reservation-service cannot answer. */
    public long bookingCount(String roomId) {
        try {
            JsonNode body = restClient.get().uri("/internal/rooms/{id}/booking-count", roomId).retrieve().body(JsonNode.class);
            JsonNode data = body == null ? null : body.path("data");
            if (data == null || !data.isNumber()) {
                throw new ExternalServiceException("reservation-service gave no booking count");
            }
            return data.asLong();
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not count bookings for room {}: {}", roomId, e.getMessage());
            throw new ExternalServiceException("Could not check the room's bookings right now. Try again in a minute", e);
        }
    }
}

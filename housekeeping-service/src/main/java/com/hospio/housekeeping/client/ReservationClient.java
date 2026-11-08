package com.hospio.housekeeping.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Who is in a room, so minibar and laundry can be charged to the stay. */
@Slf4j
@Component
public class ReservationClient {

    private final RestClient restClient;

    public ReservationClient(@Value("${services.reservation.url}") String url, JwtUtil jwtUtil,
                             @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
    }

    public record ActiveStay(String bookingId, String guestName, String roomNumber) {
    }

    public Optional<ActiveStay> activeStay(String roomId) {
        try {
            JsonNode body = restClient.get().uri("/internal/rooms/{id}/active-booking", roomId).retrieve().body(JsonNode.class);
            JsonNode d = body == null ? null : body.path("data");
            if (d == null || d.isMissingNode() || d.isNull()) {
                return Optional.empty();
            }
            return Optional.of(new ActiveStay(d.path("id").asText(), d.path("guestName").asText(), d.path("roomNumber").asText()));
        } catch (Exception e) {
            log.debug("No active stay for room {}: {}", roomId, e.getMessage());
            return Optional.empty();
        }
    }

    public ActiveStay requireActiveStay(String roomId) {
        return activeStay(roomId).orElseThrow(() -> new BadRequestException("Nobody is checked in to that room"));
    }
}

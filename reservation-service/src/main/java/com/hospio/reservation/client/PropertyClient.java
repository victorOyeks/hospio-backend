package com.hospio.reservation.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Rooms of a type and the nightly price for a date, from property-service. */
@Slf4j
@Component
public class PropertyClient {

    private final RestClient restClient;

    public PropertyClient(@Value("${services.property.url}") String propertyUrl, JwtUtil jwtUtil,
                          @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(propertyUrl, jwtUtil, internalApiKey);
    }

    public record RoomSummary(String id, String number, String roomTypeId, String roomTypeName, boolean inService) {
    }

    public List<RoomSummary> rooms(String hotelId, String roomTypeId) {
        try {
            JsonNode body = restClient.get()
                    .uri(u -> u.path("/internal/rooms").queryParam("hotelId", hotelId)
                            .queryParamIfPresent("roomTypeId", Optional.ofNullable(roomTypeId)).build())
                    .retrieve().body(JsonNode.class);
            List<RoomSummary> out = new ArrayList<>();
            if (body != null) {
                for (JsonNode r : body.path("data")) {
                    out.add(toRoom(r));
                }
            }
            return out;
        } catch (Exception e) {
            log.error("Could not read rooms from property-service", e);
            throw new ExternalServiceException("Rooms are not available right now", e);
        }
    }

    public RoomSummary room(String roomId) {
        try {
            JsonNode r = restClient.get().uri("/internal/rooms/{id}", roomId).retrieve().body(JsonNode.class);
            if (r == null || r.path("data").isMissingNode()) {
                throw new ExternalServiceException("Room not found");
            }
            return toRoom(r.path("data"));
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalServiceException("That room could not be read", e);
        }
    }

    public String roomTypeName(String roomTypeId) {
        try {
            JsonNode r = restClient.get().uri("/internal/room-types/{id}", roomTypeId).retrieve().body(JsonNode.class);
            return r == null ? null : r.path("data").path("name").asText(null);
        } catch (Exception e) {
            log.warn("Could not read room type {}: {}", roomTypeId, e.getMessage());
            return null;
        }
    }

    public BigDecimal price(String roomTypeId, LocalDate date) {
        try {
            JsonNode r = restClient.get().uri("/internal/room-types/{id}/price?date={d}", roomTypeId, date.toString()).retrieve().body(JsonNode.class);
            if (r == null || r.path("data").isMissingNode() || r.path("data").isNull()) {
                throw new ExternalServiceException("No price for that room type");
            }
            return new BigDecimal(r.path("data").asText());
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalServiceException("Prices are not available right now", e);
        }
    }

    private static RoomSummary toRoom(JsonNode r) {
        return new RoomSummary(r.path("id").asText(), r.path("number").asText(), r.path("roomTypeId").asText(),
                r.path("roomTypeName").asText(null), "IN_SERVICE".equals(r.path("status").asText()));
    }
}

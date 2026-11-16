package com.hospio.reservation.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
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

    /** A room as property-service's /internal/rooms and /internal/rooms/{id} give it (RoomResponse), the fields read here. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Room(String id, String number, String roomTypeId, String roomTypeName, String status) {
    }

    /** A room type as GET /internal/room-types/{id} gives it (RoomTypeResponse), the field read here. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoomType(String name) {
    }

    private static final ParameterizedTypeReference<ApiResponse<List<Room>>> ROOMS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<Room>> ROOM = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<RoomType>> ROOM_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<BigDecimal>> PRICE = new ParameterizedTypeReference<>() {
    };

    public List<RoomSummary> rooms(String hotelId, String roomTypeId) {
        try {
            ApiResponse<List<Room>> body = restClient.get()
                    .uri(u -> u.path("/internal/rooms").queryParam("hotelId", hotelId)
                            .queryParamIfPresent("roomTypeId", Optional.ofNullable(roomTypeId)).build())
                    .retrieve().body(ROOMS);
            List<RoomSummary> out = new ArrayList<>();
            if (body != null && body.data() != null) {
                for (Room r : body.data()) {
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
            ApiResponse<Room> r = restClient.get().uri("/internal/rooms/{id}", roomId).retrieve().body(ROOM);
            if (r == null || r.data() == null) {
                throw new ExternalServiceException("Room not found");
            }
            return toRoom(r.data());
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalServiceException("That room could not be read", e);
        }
    }

    public String roomTypeName(String roomTypeId) {
        try {
            ApiResponse<RoomType> r = restClient.get().uri("/internal/room-types/{id}", roomTypeId).retrieve().body(ROOM_TYPE);
            return r == null || r.data() == null ? null : r.data().name();
        } catch (Exception e) {
            log.warn("Could not read room type {}: {}", roomTypeId, e.getMessage());
            return null;
        }
    }

    public BigDecimal price(String roomTypeId, LocalDate date) {
        try {
            ApiResponse<BigDecimal> r = restClient.get().uri("/internal/room-types/{id}/price?date={d}", roomTypeId, date.toString()).retrieve().body(PRICE);
            if (r == null || r.data() == null) {
                throw new ExternalServiceException("No price for that room type");
            }
            return r.data();
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalServiceException("Prices are not available right now", e);
        }
    }

    private static RoomSummary toRoom(Room r) {
        return new RoomSummary(r.id(), r.number(), r.roomTypeId(), r.roomTypeName(), "IN_SERVICE".equals(r.status()));
    }
}

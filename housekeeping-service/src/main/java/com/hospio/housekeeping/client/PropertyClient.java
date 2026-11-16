package com.hospio.housekeeping.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Every room of a hotel from property-service, so the room board lists rooms housekeeping has never touched. */
@Slf4j
@Component
public class PropertyClient {

    private final RestClient restClient;

    public PropertyClient(@Value("${services.property.url}") String url, JwtUtil jwtUtil,
                          @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
    }

    public record Room(String id, String number) {
    }

    /** The part of property-service's room this client reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoomRef(String id, String number) {
    }

    private static final ParameterizedTypeReference<ApiResponse<List<RoomRef>>> ROOMS = new ParameterizedTypeReference<>() {
    };

    /** Empty when property-service can't be reached; the caller then shows only the rooms it already has a status for. */
    public List<Room> rooms(String hotelId) {
        try {
            ApiResponse<List<RoomRef>> body = restClient.get().uri("/internal/rooms?hotelId={h}", hotelId).retrieve().body(ROOMS);
            List<Room> out = new ArrayList<>();
            if (body != null && body.data() != null) {
                for (RoomRef r : body.data()) {
                    if (r != null) {
                        out.add(new Room(r.id() == null ? "" : r.id(), r.number()));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Could not read rooms of hotel {} from property-service: {}", hotelId, e.getMessage());
            return List.of();
        }
    }
}

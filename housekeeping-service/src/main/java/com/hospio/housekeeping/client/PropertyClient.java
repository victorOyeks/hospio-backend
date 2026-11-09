package com.hospio.housekeeping.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    /** Empty when property-service can't be reached; the caller then shows only the rooms it already has a status for. */
    public List<Room> rooms(String hotelId) {
        try {
            JsonNode body = restClient.get().uri("/internal/rooms?hotelId={h}", hotelId).retrieve().body(JsonNode.class);
            List<Room> out = new ArrayList<>();
            if (body != null) {
                for (JsonNode r : body.path("data")) {
                    out.add(new Room(r.path("id").asText(), r.path("number").asText(null)));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Could not read rooms of hotel {} from property-service: {}", hotelId, e.getMessage());
            return List.of();
        }
    }
}

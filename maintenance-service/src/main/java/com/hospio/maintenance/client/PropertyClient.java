package com.hospio.maintenance.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Rooms in property-service: look one up, take it out of order, put it back. */
@Slf4j
@Component
public class PropertyClient {

    private final RestClient restClient;

    public PropertyClient(@Value("${services.property.url}") String url, JwtUtil jwtUtil,
                          @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
    }

    public record Room(String id, String number, boolean inService) {
    }

    /** The room, or 400 "That room isn't in this hotel" when property-service doesn't know it. */
    public Room room(String roomId) {
        try {
            JsonNode body = restClient.get().uri("/internal/rooms/{id}", roomId).retrieve().body(JsonNode.class);
            JsonNode d = body == null ? null : body.path("data");
            if (d == null || d.isMissingNode() || d.isNull()) {
                throw new BadRequestException("That room isn't in this hotel");
            }
            return new Room(d.path("id").asText(), d.path("number").asText(null), !"OUT_OF_ORDER".equals(d.path("status").asText()));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 400) {
                throw new BadRequestException("That room isn't in this hotel");
            }
            throw new BadRequestException("Can't reach the rooms list right now · try again");
        }
    }

    public void outOfOrder(String roomId, String reason, LocalDate until) {
        Map<String, Object> body = new HashMap<>();
        body.put("reason", reason);
        if (until != null) {
            body.put("until", until.toString());
        }
        try {
            restClient.post().uri("/internal/rooms/{id}/out-of-order", roomId).body(body).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new BadRequestException("Couldn't take the room out of order right now · try again");
        }
    }

    public void backInService(String roomId) {
        try {
            restClient.post().uri("/internal/rooms/{id}/back-in-service", roomId).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new BadRequestException("Couldn't put the room back in service right now · try again");
        }
    }
}

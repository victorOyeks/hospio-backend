package com.hospio.fnb.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Room numbers, so a refusal can say "Room 204" rather than an id. */
@Component
public class PropertyClient {

    private final RestClient restClient;

    public PropertyClient(@Value("${services.property.url}") String url, JwtUtil jwtUtil, @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
    }

    /** The room's number, or null when property-service can't say. */
    public String roomNumber(String roomId) {
        try {
            JsonNode body = restClient.get().uri("/internal/rooms/{id}", roomId).retrieve().body(JsonNode.class);
            JsonNode n = body == null ? null : body.path("data").path("number");
            return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
        } catch (RuntimeException e) {
            return null;
        }
    }
}

package com.hospio.housekeeping.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** "Report a problem" opens a repair in maintenance-service. Returns the repair id, or null if it could not be opened. */
@Slf4j
@Component
public class MaintenanceClient {

    private final RestClient restClient;

    public MaintenanceClient(@Value("${services.maintenance.url}") String url, JwtUtil jwtUtil,
                             @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
    }

    public String openRepair(String roomId, String roomNumber, String description, String photoKey) {
        try {
            JsonNode body = restClient.post().uri("/internal/repairs")
                    .body(Map.of("roomId", roomId == null ? "" : roomId, "roomNumber", roomNumber == null ? "" : roomNumber,
                            "title", description.length() > 80 ? description.substring(0, 80) : description, "description", description,
                            "source", "HOUSEKEEPING", "photoKey", photoKey == null ? "" : photoKey))
                    .retrieve().body(JsonNode.class);
            return body == null ? null : body.path("data").path("id").asText(null);
        } catch (Exception e) {
            log.warn("Could not open a repair for room {}: {}", roomNumber, e.getMessage());
            return null;
        }
    }
}

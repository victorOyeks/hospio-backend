package com.hospio.housekeeping.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * "Report a problem" opens a work order in maintenance-service. Returns the work order id, or null if maintenance could not be
 * reached (the report is kept as PENDING). A refusal maintenance gives in words (a guest is checked in, so the room can't go out of
 * order) comes back as a {@link BadRequestException} with that sentence, and nothing is opened.
 */
@Slf4j
@Component
public class MaintenanceClient {

    private final RestClient restClient;

    public MaintenanceClient(@Value("${services.maintenance.url}") String url, JwtUtil jwtUtil,
                             @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
    }

    public String openRepair(String roomId, String roomNumber, String description, String photoKey) {
        return openRepair(roomId, roomNumber, description, photoKey, null, false);
    }

    public String openRepair(String roomId, String roomNumber, String description, String photoKey, String priority, boolean outOfOrder) {
        try {
            Map<String, Object> request = new java.util.HashMap<>(Map.of("roomId", roomId == null ? "" : roomId, "roomNumber", roomNumber == null ? "" : roomNumber,
                    "title", description.length() > 80 ? description.substring(0, 80) : description, "description", description,
                    "source", "HOUSEKEEPING", "photoKey", photoKey == null ? "" : photoKey));
            if (priority != null) {
                request.put("priority", priority);
            }
            JsonNode body = restClient.post().uri("/internal/repairs?outOfOrder={o}", outOfOrder)
                    .body(request)
                    .retrieve().body(JsonNode.class);
            return body == null ? null : body.path("data").path("id").asText(null);
        } catch (HttpClientErrorException e) {
            String message = null;
            try {
                message = e.getResponseBodyAs(JsonNode.class).path("message").asText(null);
            } catch (Exception ignored) {
                // no readable body: fall through to the generic sentence
            }
            log.warn("Maintenance refused a work order for room {}: {}", roomNumber, message == null ? e.getStatusCode() : message);
            throw new BadRequestException(message == null || message.isBlank() ? "Maintenance couldn't take this report · try again" : message);
        } catch (Exception e) {
            log.warn("Could not open a repair for room {}: {}", roomNumber, e.getMessage());
            return null;
        }
    }
}

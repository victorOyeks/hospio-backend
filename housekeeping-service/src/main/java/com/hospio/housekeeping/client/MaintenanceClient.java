package com.hospio.housekeeping.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
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

    /** The part of maintenance-service's work order this client reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RepairRef(String id) {
    }

    /** The envelope of a refusal: only its sentence is read. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Refusal(String message) {
    }

    private static final ParameterizedTypeReference<ApiResponse<RepairRef>> REPAIR = new ParameterizedTypeReference<>() {
    };

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
            if ((roomId == null || roomId.isBlank()) && roomNumber != null && !roomNumber.isBlank()) {
                // A public area ("Lobby", "Floor 2 corridor"): maintenance needs it as the place, not as a room number.
                request.put("area", roomNumber.length() > 100 ? roomNumber.substring(0, 100) : roomNumber);
                request.put("roomNumber", "");
            }
            ApiResponse<RepairRef> body = restClient.post().uri("/internal/repairs?outOfOrder={o}", outOfOrder)
                    .body(request)
                    .retrieve().body(REPAIR);
            return body == null || body.data() == null ? null : body.data().id();
        } catch (HttpClientErrorException e) {
            String message = null;
            try {
                Refusal refusal = e.getResponseBodyAs(Refusal.class);
                message = refusal == null ? null : refusal.message();
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

package com.hospio.maintenance.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Parts used on a work order come out of the hotel's Maintenance store (inventory-service internal stock moves). */
@Component
public class InventoryClient {

    private final RestClient restClient;
    private final ObjectMapper mapper;

    public InventoryClient(@Value("${services.inventory.url}") String url, JwtUtil jwtUtil,
                           @Value("${services.internal.api-key}") String internalApiKey, ObjectMapper mapper) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
        this.mapper = mapper;
    }

    public record Part(String itemId, String name, String unit, BigDecimal quantity, BigDecimal unitCost, BigDecimal value) {
    }

    /** One line of inventory-service's stock move result. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MovedLine(String itemId, String name, String unit, BigDecimal quantity, BigDecimal unitCost, BigDecimal value) {
    }

    /** inventory-service's stock move result: only its lines are read. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MoveResult(List<MovedLine> lines) {
    }

    /** The envelope of a refusal: only its sentence is read. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Refusal(String message) {
    }

    private static final ParameterizedTypeReference<ApiResponse<MoveResult>> MOVE = new ParameterizedTypeReference<>() {
    };

    /** USED movements in the MAINTENANCE store under {@code reference}; the store's own sentence on a refusal. */
    public List<Part> useParts(String reference, String note, List<Map<String, Object>> lines) {
        try {
            ApiResponse<MoveResult> body = restClient.post().uri("/internal/stock/moves")
                    .body(Map.of("reference", reference, "kind", "USE", "fromKind", "MAINTENANCE", "team", "MAINTENANCE", "note", note, "lines", lines))
                    .retrieve().body(MOVE);
            List<Part> out = new ArrayList<>();
            if (body != null && body.data() != null && body.data().lines() != null) {
                for (MovedLine l : body.data().lines()) {
                    if (l != null) {
                        out.add(new Part(l.itemId() == null ? "" : l.itemId(), l.name(), l.unit(), zeroIfNull(l.quantity()),
                                zeroIfNull(l.unitCost()), zeroIfNull(l.value())));
                    }
                }
            }
            return out;
        } catch (RestClientResponseException e) {
            String message = null;
            try {
                message = mapper.readValue(e.getResponseBodyAsString(), Refusal.class).message();
            } catch (Exception ignored) {
                // fall through to the generic sentence
            }
            throw new BadRequestException(message != null && !message.isBlank() ? message : "The Maintenance store couldn't give those parts right now");
        } catch (RuntimeException e) {
            throw new BadRequestException("Can't reach the store right now · try again");
        }
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

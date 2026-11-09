package com.hospio.maintenance.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
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

    /** USED movements in the MAINTENANCE store under {@code reference}; the store's own sentence on a refusal. */
    public List<Part> useParts(String reference, String note, List<Map<String, Object>> lines) {
        try {
            JsonNode body = restClient.post().uri("/internal/stock/moves")
                    .body(Map.of("reference", reference, "kind", "USE", "fromKind", "MAINTENANCE", "team", "MAINTENANCE", "note", note, "lines", lines))
                    .retrieve().body(JsonNode.class);
            List<Part> out = new ArrayList<>();
            if (body != null) {
                for (JsonNode l : body.path("data").path("lines")) {
                    out.add(new Part(l.path("itemId").asText(), l.path("name").asText(null), l.path("unit").asText(null),
                            l.path("quantity").decimalValue(), l.path("unitCost").decimalValue(), l.path("value").decimalValue()));
                }
            }
            return out;
        } catch (RestClientResponseException e) {
            String message = null;
            try {
                message = mapper.readTree(e.getResponseBodyAsString()).path("message").asText(null);
            } catch (Exception ignored) {
                // fall through to the generic sentence
            }
            throw new BadRequestException(message != null && !message.isBlank() ? message : "The Maintenance store couldn't give those parts right now");
        } catch (RuntimeException e) {
            throw new BadRequestException("Can't reach the store right now · try again");
        }
    }
}

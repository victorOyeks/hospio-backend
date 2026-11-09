package com.hospio.housekeeping.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * What laundry asks other services (docs/laundry-maintenance.md): whether a stay has an open bill (folio), whether a payment was approved
 * (payment), and moving linen and chemicals between the Housekeeping and Laundry stores (inventory internal stock moves).
 */
@Component
public class LaundryClients {

    private final RestClient folio;
    private final RestClient payment;
    private final RestClient inventory;
    private final ObjectMapper mapper;

    public LaundryClients(@Value("${services.folio.url}") String folioUrl, @Value("${services.payment.url}") String paymentUrl,
                          @Value("${services.inventory.url}") String inventoryUrl, JwtUtil jwtUtil,
                          @Value("${services.internal.api-key}") String internalApiKey, ObjectMapper mapper) {
        this.folio = ServiceClients.internal(folioUrl, jwtUtil, internalApiKey);
        this.payment = ServiceClients.internal(paymentUrl, jwtUtil, internalApiKey);
        this.inventory = ServiceClients.internal(inventoryUrl, jwtUtil, internalApiKey);
        this.mapper = mapper;
    }

    /** True when the booking has an open folio, false on 404; 400 when folio-service can't answer. */
    public boolean hasOpenFolio(String bookingId) {
        try {
            folio.get().uri("/internal/folios/open?bookingId={b}", bookingId).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return false;
            }
            throw new BadRequestException("Can't check the room's bill right now · try again");
        } catch (RuntimeException e) {
            throw new BadRequestException("Can't check the room's bill right now · try again");
        }
    }

    public record Payment(String id, String status, BigDecimal amount, String method) {
    }

    /** The payment as payment-service has it; 400 "That payment isn't in this hotel" when it doesn't know it. */
    public Payment payment(String paymentId) {
        try {
            JsonNode d = payment.get().uri("/internal/payments/{id}", paymentId).retrieve().body(JsonNode.class).path("data");
            return new Payment(d.path("id").asText(), d.path("status").asText(null), d.path("amount").decimalValue(), d.path("method").asText(null));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 400) {
                throw new BadRequestException("That payment isn't in this hotel");
            }
            throw new BadRequestException("Can't check the payment right now · try again");
        } catch (RuntimeException e) {
            throw new BadRequestException("Can't check the payment right now · try again");
        }
    }

    public record Moved(String itemId, String name, String unit, BigDecimal quantity, BigDecimal unitCost, BigDecimal value) {
    }

    /** Moves stock (USE, WASTE or TRANSFER between store kinds); the store's own sentence on a refusal. Idempotent by reference. */
    public List<Moved> move(String reference, String kind, String fromKind, String toKind, String team, String note, String category,
                            Map<String, BigDecimal> lines) {
        List<Map<String, Object>> body = new ArrayList<>();
        lines.forEach((item, q) -> body.add(Map.of("itemId", item, "quantity", q)));
        Map<String, Object> request = new HashMap<>();
        request.put("reference", reference);
        request.put("kind", kind);
        request.put("fromKind", fromKind);
        request.put("toKind", toKind);
        request.put("team", team);
        request.put("note", note);
        request.put("category", category);
        request.put("lines", body);
        try {
            JsonNode res = inventory.post().uri("/internal/stock/moves").body(request).retrieve().body(JsonNode.class);
            List<Moved> out = new ArrayList<>();
            if (res != null) {
                for (JsonNode l : res.path("data").path("lines")) {
                    out.add(new Moved(l.path("itemId").asText(), l.path("name").asText(null), l.path("unit").asText(null), l.path("quantity").decimalValue(),
                            l.path("unitCost").decimalValue(), l.path("value").decimalValue()));
                }
            }
            return out;
        } catch (RestClientResponseException e) {
            String message = null;
            try {
                message = mapper.readTree(e.getResponseBodyAsString()).path("message").asText(null);
            } catch (Exception ignored) {
                // generic sentence below
            }
            throw new BadRequestException(message != null && !message.isBlank() ? message : "The store couldn't move that linen right now");
        } catch (RuntimeException e) {
            throw new BadRequestException("Can't reach the store right now · try again");
        }
    }

    /** The hotel's linen items (inventory category Linen) with names and units, for a batch's lines. */
    public Map<String, String[]> linenItems() {
        Map<String, String[]> out = new HashMap<>();
        try {
            JsonNode res = inventory.get().uri("/internal/items").retrieve().body(JsonNode.class);
            if (res != null) {
                for (JsonNode i : res.path("data")) {
                    out.put(i.path("id").asText(), new String[]{i.path("name").asText(), i.path("unit").asText(null), i.path("category").asText(""),
                            String.valueOf(i.path("active").asBoolean(true))});
                }
            }
            return out;
        } catch (RuntimeException e) {
            throw new BadRequestException("Can't read the linen list right now · try again");
        }
    }
}

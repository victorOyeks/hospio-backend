package com.hospio.housekeeping.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * What laundry asks other services (docs/laundry-maintenance.md): whether a stay has an open bill (folio), whether a payment was approved
 * (payment), and moving linen and chemicals between the Housekeeping and Laundry stores (inventory internal stock moves).
 */
@Component
public class LaundryClients {

    /** The part of payment-service's payment laundry reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentRef(String id, String status, BigDecimal amount, String method) {
    }

    /** One line of inventory-service's stock move result. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MovedLine(String itemId, String name, String unit, BigDecimal quantity, BigDecimal unitCost, BigDecimal value) {
    }

    /** inventory-service's stock move result: only its lines are read. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MoveResult(List<MovedLine> lines) {
    }

    /** The part of inventory-service's item view laundry reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ItemRef(String id, String name, String unit, String category, Boolean active) {
    }

    /** The envelope of a refusal: only its sentence is read. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Refusal(String message) {
    }

    private static final ParameterizedTypeReference<ApiResponse<PaymentRef>> PAYMENT = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<MoveResult>> MOVE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<ApiResponse<List<ItemRef>>> ITEMS = new ParameterizedTypeReference<>() {
    };

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

    /** The payment as payment-service has it; 400 "That payment isn't in this " + com.hospio.common.venue.VenuePlaces.here().place() when it doesn't know it. */
    public Payment payment(String paymentId) {
        try {
            ApiResponse<PaymentRef> body = payment.get().uri("/internal/payments/{id}", paymentId).retrieve().body(PAYMENT);
            if (body == null) {
                throw new BadRequestException("Can't check the payment right now · try again");
            }
            PaymentRef d = body.data() == null ? new PaymentRef(null, null, null, null) : body.data();
            return new Payment(d.id() == null ? "" : d.id(), d.status(), zeroIfNull(d.amount()), d.method());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 400) {
                throw new BadRequestException("That payment isn't in this " + com.hospio.common.venue.VenuePlaces.here().place());
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
        List<com.hospio.common.inventory.StockMoveRequest.Line> body = new ArrayList<>();
        lines.forEach((item, q) -> body.add(new com.hospio.common.inventory.StockMoveRequest.Line(item, q)));
        com.hospio.common.inventory.StockMoveRequest request =
                new com.hospio.common.inventory.StockMoveRequest(reference, kind, fromKind, toKind, team, note, category, body);
        try {
            ApiResponse<MoveResult> res = inventory.post().uri("/internal/stock/moves").body(request).retrieve().body(MOVE);
            List<Moved> out = new ArrayList<>();
            if (res != null && res.data() != null && res.data().lines() != null) {
                for (MovedLine l : res.data().lines()) {
                    if (l != null) {
                        out.add(new Moved(l.itemId() == null ? "" : l.itemId(), l.name(), l.unit(), zeroIfNull(l.quantity()),
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
                // generic sentence below
            }
            throw new BadRequestException(message != null && !message.isBlank() ? message : "The store couldn't move that linen right now");
        } catch (RuntimeException e) {
            throw new BadRequestException("Can't reach the store right now · try again");
        }
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** The hotel's linen items (inventory category Linen) with names and units, for a batch's lines. */
    public Map<String, String[]> linenItems() {
        Map<String, String[]> out = new HashMap<>();
        try {
            ApiResponse<List<ItemRef>> res = inventory.get().uri("/internal/items").retrieve().body(ITEMS);
            if (res != null && res.data() != null) {
                for (ItemRef i : res.data()) {
                    if (i != null) {
                        out.put(i.id() == null ? "" : i.id(), new String[]{i.name() == null ? "" : i.name(), i.unit(),
                                i.category() == null ? "" : i.category(), String.valueOf(!Boolean.FALSE.equals(i.active()))});
                    }
                }
            }
            return out;
        } catch (RuntimeException e) {
            throw new BadRequestException("Can't read the linen list right now · try again");
        }
    }
}

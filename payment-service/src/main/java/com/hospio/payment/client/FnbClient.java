package com.hospio.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** A kitchen or bar order's state from fnb-service, so money is never taken for an order that is cancelled, closed or already settled. */
@Slf4j
@Component
public class FnbClient {

    /** {@code status} null when fnb-service couldn't be reached (callers let the payment through). */
    /** {@code desk} is the desk whose income the order is: BAR for a bar outlet, KITCHEN for a kitchen or restaurant. */
    /**
     * {@code refusal}: fnb's reason no payment can be taken as asked (a split bill paid without naming a part, a part already paid, an
     * outlet the payer isn't assigned to). {@code partLabel}: "Part 2 of 3" when a part of a split bill is being paid.
     */
    public record OrderDue(String hotelId, String number, String status, boolean settled, BigDecimal due, String desk, String openedBy,
                           String refusal, String partLabel) {
        public OrderDue(String hotelId, String number, String status, boolean settled, BigDecimal due, String desk, String openedBy) {
            this(hotelId, number, status, settled, due, desk, openedBy, null, null);
        }

        public OrderDue(String hotelId, String number, String status, boolean settled, BigDecimal due, String desk) {
            this(hotelId, number, status, settled, due, desk, null);
        }
    }

    /** fnb-service's answer to GET /internal/orders/{id}/due. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DueBody(String hotelId, String number, String status, Boolean settled, BigDecimal due, String desk, String openedBy, String refusal,
                   String partLabel) {
    }

    private static final ParameterizedTypeReference<ApiResponse<DueBody>> DUE = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public FnbClient(@Value("${services.fnb.url}") String fnbUrl, JwtUtil jwtUtil, @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(fnbUrl, jwtUtil, internalApiKey);
    }

    /** Empty when the order doesn't exist. */
    public Optional<OrderDue> order(String orderId) {
        return order(orderId, null, null);
    }

    /** As above for one part of a split bill and for the person taking the payment (fnb checks their outlets). */
    public Optional<OrderDue> order(String orderId, String partId, String payerId) {
        try {
            ApiResponse<DueBody> body = restClient.get().uri(b -> {
                b.path("/internal/orders/{id}/due");
                if (partId != null && !partId.isBlank()) {
                    b.queryParam("partId", partId);
                }
                if (payerId != null && !payerId.isBlank()) {
                    b.queryParam("payerId", payerId);
                }
                return b.build(orderId);
            }).retrieve().body(DUE);
            DueBody d = body == null ? null : body.data();
            if (d == null) {
                return Optional.empty();
            }
            return Optional.of(new OrderDue(d.hotelId(), d.number(), d.status(), Boolean.TRUE.equals(d.settled()), d.due(), d.desk(), d.openedBy(), d.refusal(), d.partLabel()));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Could not read order {} from fnb-service: {}", orderId, e.getMessage());
            return Optional.of(new OrderDue(null, null, null, false, null, null));
        }
    }
}

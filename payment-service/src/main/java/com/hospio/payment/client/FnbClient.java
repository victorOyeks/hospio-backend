package com.hospio.payment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** A kitchen or bar order's state from fnb-service, so money is never taken for an order that is cancelled, closed or already settled. */
@Slf4j
@Component
public class FnbClient {

    /** {@code status} null when fnb-service couldn't be reached (callers let the payment through). */
    public record OrderDue(String hotelId, String number, String status, boolean settled, BigDecimal due) {
    }

    private final RestClient restClient;

    public FnbClient(@Value("${services.fnb.url}") String fnbUrl, JwtUtil jwtUtil, @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(fnbUrl, jwtUtil, internalApiKey);
    }

    /** Empty when the order doesn't exist. */
    public Optional<OrderDue> order(String orderId) {
        try {
            JsonNode body = restClient.get().uri("/internal/orders/{id}/due", orderId).retrieve().body(JsonNode.class);
            JsonNode d = body == null ? null : body.path("data");
            if (d == null || d.isMissingNode() || d.isNull()) {
                return Optional.empty();
            }
            return Optional.of(new OrderDue(d.path("hotelId").asText(null), d.path("number").asText(null), d.path("status").asText(null),
                    d.path("settled").asBoolean(false), d.path("due").isNumber() ? d.path("due").decimalValue() : null));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Could not read order {} from fnb-service: {}", orderId, e.getMessage());
            return Optional.of(new OrderDue(null, null, null, false, null));
        }
    }
}

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
    public record OrderDue(String hotelId, String number, String status, boolean settled, BigDecimal due, String desk) {
    }

    /** fnb-service's answer to GET /internal/orders/{id}/due. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DueBody(String hotelId, String number, String status, Boolean settled, BigDecimal due, String desk) {
    }

    private static final ParameterizedTypeReference<ApiResponse<DueBody>> DUE = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public FnbClient(@Value("${services.fnb.url}") String fnbUrl, JwtUtil jwtUtil, @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(fnbUrl, jwtUtil, internalApiKey);
    }

    /** Empty when the order doesn't exist. */
    public Optional<OrderDue> order(String orderId) {
        try {
            ApiResponse<DueBody> body = restClient.get().uri("/internal/orders/{id}/due", orderId).retrieve().body(DUE);
            DueBody d = body == null ? null : body.data();
            if (d == null) {
                return Optional.empty();
            }
            return Optional.of(new OrderDue(d.hotelId(), d.number(), d.status(), Boolean.TRUE.equals(d.settled()), d.due(), d.desk()));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Could not read order {} from fnb-service: {}", orderId, e.getMessage());
            return Optional.of(new OrderDue(null, null, null, false, null, null));
        }
    }
}

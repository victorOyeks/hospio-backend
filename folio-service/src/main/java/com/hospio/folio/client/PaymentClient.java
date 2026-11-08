package com.hospio.folio.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** payment-service, read-only: what has already been paid against a booking, so the folio opens with it. */
@Slf4j
@Component
public class PaymentClient {

    private final RestClient restClient;

    public PaymentClient(@Value("${services.payment.url}") String url, JwtUtil jwtUtil,
                         @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
    }

    public record ApprovedPayment(String id, BigDecimal amount, String method, String takenBy) {
    }

    /** Approved payments for the booking (deposits taken before check-in). Empty when payment-service is unreachable. */
    public List<ApprovedPayment> approvedForBooking(String bookingId) {
        try {
            JsonNode body = restClient.get().uri("/internal/payments/by-booking/{id}", bookingId).retrieve().body(JsonNode.class);
            JsonNode data = body == null ? null : body.path("data");
            List<ApprovedPayment> out = new ArrayList<>();
            if (data != null && data.isArray()) {
                for (JsonNode p : data) {
                    if ("APPROVED".equals(p.path("status").asText())) {
                        out.add(new ApprovedPayment(p.path("id").asText(), new BigDecimal(p.path("amount").asText("0")),
                                p.path("method").asText("CASH"), p.path("requestedByName").asText(null)));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Could not read payments for booking {}: {}", bookingId, e.getMessage());
            return List.of();
        }
    }
}

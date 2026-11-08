package com.hospio.payment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Hotel setup → Payments: which methods are allowed and the receipt rule. Defaults when unreachable. */
@Slf4j
@Component
public class HotelConfigClient {

    private final RestClient restClient;

    public HotelConfigClient(@Value("${services.hotelconfig.url}") String url, JwtUtil jwtUtil,
                             @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.internal(url, jwtUtil, internalApiKey);
    }

    public record PaymentSettings(boolean allowCash, boolean allowCard, boolean allowTransfer, String receipts) {
        public static PaymentSettings defaults() {
            return new PaymentSettings(true, true, true, "ASK");
        }
    }

    public PaymentSettings paymentSettings(String hotelId) {
        try {
            JsonNode body = restClient.get().uri("/internal/settings/PAYMENTS?hotelId={h}", hotelId).retrieve().body(JsonNode.class);
            JsonNode data = body == null ? null : body.path("data");
            if (data == null || data.isMissingNode() || data.isEmpty()) {
                return PaymentSettings.defaults();
            }
            return new PaymentSettings(data.path("allowCash").asBoolean(true), data.path("allowCard").asBoolean(true),
                    data.path("allowTransfer").asBoolean(true), data.path("receipts").asText("ASK"));
        } catch (Exception e) {
            log.warn("Could not read payment settings for hotel {}: {}", hotelId, e.getMessage());
            return PaymentSettings.defaults();
        }
    }
}

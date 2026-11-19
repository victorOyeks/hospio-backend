package com.hospio.platform.client;

import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Pauses or resumes POS card payments for a hotel in payment-service (an unpaid monthly invoice, docs/plan-monthly-invoices.md).
 * The daily fee debit this client also ran was removed on 19 Nov 2026: Hospio360 bills one monthly invoice and debits nothing.
 */
@Slf4j
@Component
public class PosPauseClient {

    private final RestClient client;

    public PosPauseClient(@Value("${services.payment.url}") String paymentUrl, JwtUtil jwtUtil,
                          @Value("${services.internal.api-key}") String internalApiKey) {
        this.client = ServiceClients.system(paymentUrl, jwtUtil, internalApiKey);
    }

    public void pausePos(String businessId, String hotelId, boolean paused) {
        try {
            client.post().uri(b -> b.path("/internal/pos/pause").queryParam("businessId", businessId).queryParam("hotelId", hotelId)
                            .queryParam("paused", paused).build())
                    .header("X-Hotel-Id", hotelId).retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("POS pause={} for {} not applied: {}", paused, hotelId, e.getMessage());
        }
    }
}

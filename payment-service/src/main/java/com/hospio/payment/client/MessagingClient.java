package com.hospio.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import com.hospio.payment.dto.ReceiptOutlook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * messaging-service's /internal/deliveries/outlook: what would become of a guest WhatsApp sent now (guest messages off, staging's
 * allow-list, …), so the desk is told the truth about a receipt (docs/whatsapp-receipts.md). Never throws: UNKNOWN when unreachable.
 */
@Slf4j
@Component
public class MessagingClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Outlook(String outcome, String reason, Boolean guestMessagesOn) {
    }

    private static final ParameterizedTypeReference<ApiResponse<Outlook>> OUTLOOK = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public MessagingClient(@Value("${services.messaging.url}") String url, JwtUtil jwtUtil,
                           @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(url, jwtUtil, internalApiKey);
    }

    /** A RECEIPT on WhatsApp at this hotel to {@code to} (null: only whether the hotel's guest messages would go). */
    public ReceiptOutlook receiptOutlook(String hotelId, String businessId, String to) {
        if (hotelId == null) {
            return ReceiptOutlook.unknown();
        }
        try {
            // Digits only (messaging-service reads the number the same way), so a "+" never turns into a space in the query.
            String digits = to == null ? "" : to.replaceAll("[^0-9]", "");
            ApiResponse<Outlook> body = restClient.get()
                    .uri("/internal/deliveries/outlook?hotelId={h}&businessId={b}&kind=RECEIPT&channel=WHATSAPP&to={t}", hotelId,
                            businessId == null ? "" : businessId, digits)
                    .retrieve().body(OUTLOOK);
            Outlook o = body == null ? null : body.data();
            return o == null || o.outcome() == null ? ReceiptOutlook.unknown() : new ReceiptOutlook(o.outcome(), o.reason(), o.guestMessagesOn());
        } catch (Exception e) {
            log.warn("Could not ask messaging-service about a receipt at hotel {}: {}", hotelId, e.getMessage());
            return ReceiptOutlook.unknown();
        }
    }
}

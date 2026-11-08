package com.hospio.common.integrations;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The POS provider (Moniepoint, OPay, ...). payment-service sends card and transfer requests to a
 * terminal and parses the provider's webhook; swapping providers is a new implementation of this port.
 */
public interface PosProvider {

    String name();

    /** Push a payment to a terminal; returns the provider's reference for the request. */
    String requestPayment(String terminalRef, String paymentRequestId, BigDecimal amount, String currency, String description);

    /** Translate a webhook body into a result payment-service understands. */
    WebhookResult parseWebhook(Map<String, Object> body, Map<String, String> headers);

    /** Ask the provider about a request (Verify). Empty when the provider has no query API. */
    default java.util.Optional<WebhookResult> query(String providerRef) {
        return java.util.Optional.empty();
    }

    enum Outcome { APPROVED, DECLINED, NO_RESPONSE, TERMINAL_OFFLINE, UNKNOWN }

    record WebhookResult(String providerRef, String paymentRequestId, Outcome outcome, BigDecimal amount, String rawStatus) {
    }
}

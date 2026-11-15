package com.hospio.common.integrations;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * A POS provider Hospio360 will integrate but hasn't yet (Moniepoint, OPay). Choosing it with POS_PROVIDER refuses card
 * payments with "<provider> POS is not connected yet" instead of pretending; webhooks are read as UNKNOWN and nothing is ever
 * queried. Replace each method with the provider's API when the integration is built (docs/payments.md, POS providers).
 */
public abstract class PlaceholderPosProvider implements PosProvider {

    /** The provider's name as shown to people ("Moniepoint"). */
    protected abstract String label();

    @Override
    public String requestPayment(String terminalRef, String paymentRequestId, BigDecimal amount, String currency, String description) {
        throw new PosNotConnectedException(label());
    }

    @Override
    public WebhookResult parseWebhook(Map<String, Object> body, Map<String, String> headers) {
        return new WebhookResult(null, null, Outcome.UNKNOWN, null, "NOT_CONNECTED");
    }

    @Override
    public Optional<WebhookResult> query(String providerRef) {
        return Optional.empty();
    }
}

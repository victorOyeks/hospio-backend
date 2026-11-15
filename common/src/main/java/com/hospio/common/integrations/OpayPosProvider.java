package com.hospio.common.integrations;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * OPay POS (POS_PROVIDER=opay): a placeholder until the integration is built. To build it: {@code requestPayment} sends the
 * amount to the OPay terminal ({@code terminalRef}) and returns OPay's reference; {@code parseWebhook} checks OPay's
 * signature and maps its status to {@link Outcome}; {@code query} reads a transaction's status for the pending-payment check.
 * Keys in the environment only (OPAY_MERCHANT_ID, OPAY_SECRET_KEY, OPAY_BASE_URL).
 */
@Component
@ConditionalOnProperty(prefix = "hospio.pos", name = "provider", havingValue = "opay")
public class OpayPosProvider extends PlaceholderPosProvider {

    @Override
    public String name() {
        return "opay";
    }

    @Override
    protected String label() {
        return "OPay";
    }
}

package com.hospio.common.integrations;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Moniepoint POS (POS_PROVIDER=moniepoint): a placeholder until the integration is built. To build it:
 * {@code requestPayment} pushes the amount to the terminal (terminal serial = {@code terminalRef}) and returns Moniepoint's
 * transaction reference; {@code parseWebhook} verifies Moniepoint's signature and maps its status to {@link Outcome};
 * {@code query} looks a transaction up by reference so the pending-payment check can settle a missed webhook. Keys in the
 * environment only (MONIEPOINT_API_KEY, MONIEPOINT_SECRET_KEY, MONIEPOINT_BASE_URL).
 */
@Component
@ConditionalOnProperty(prefix = "hospio.pos", name = "provider", havingValue = "moniepoint")
public class MoniepointPosProvider extends PlaceholderPosProvider {

    @Override
    public String name() {
        return "moniepoint";
    }

    @Override
    protected String label() {
        return "Moniepoint";
    }
}

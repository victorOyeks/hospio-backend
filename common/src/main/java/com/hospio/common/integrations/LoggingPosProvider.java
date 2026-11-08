package com.hospio.common.integrations;

import java.math.BigDecimal;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Development provider: logs the request; a webhook body with {@code status} and {@code paymentRequestId} is the result. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "hospio.pos", name = "provider", havingValue = "logging", matchIfMissing = true)
public class LoggingPosProvider implements PosProvider {

    @Override
    public String name() {
        return "logging";
    }

    @Override
    public String requestPayment(String terminalRef, String paymentRequestId, BigDecimal amount, String currency, String description) {
        String ref = "LOG-" + paymentRequestId.substring(0, 8);
        log.info("[POS:{}] {} {} {} -> terminal {} ({})", ref, amount, currency, description, terminalRef, paymentRequestId);
        return ref;
    }

    @Override
    public WebhookResult parseWebhook(Map<String, Object> body, Map<String, String> headers) {
        String status = String.valueOf(body.getOrDefault("status", "UNKNOWN")).toUpperCase();
        Outcome outcome = switch (status) {
            case "APPROVED", "SUCCESS", "SUCCESSFUL" -> Outcome.APPROVED;
            case "DECLINED", "FAILED" -> Outcome.DECLINED;
            case "TIMEOUT", "NO_RESPONSE" -> Outcome.NO_RESPONSE;
            case "OFFLINE" -> Outcome.TERMINAL_OFFLINE;
            default -> Outcome.UNKNOWN;
        };
        Object id = body.get("paymentRequestId");
        Object amount = body.get("amount");
        return new WebhookResult(String.valueOf(body.get("reference")), id == null ? null : id.toString(),
                outcome, amount == null ? null : new BigDecimal(amount.toString()), status);
    }
}

package com.hospio.common.billing;

import java.math.BigDecimal;

/** What platform-service and payment-service send each other for the daily fee debit (docs/plan-daily-fees.md). */
public final class FeeDebitDtos {

    private FeeDebitDtos() {
    }

    /** A provider's debit: configured on the server, and the hotel's keys for it present; {@code reason} says what is missing. */
    public record ProviderState(String provider, boolean debitConfigured, boolean hotelKeys, String reason) {
    }

    public record DebitRequest(String hotelId, String provider, BigDecimal amount, String reference, String narration) {
    }

    /** status: APPROVED (money taken), PENDING (the provider hasn't said yet), FAILED (nothing taken). */
    public record DebitResult(String status, BigDecimal collected, String providerRef, String message) {
        public static DebitResult pending(String message) {
            return new DebitResult("PENDING", BigDecimal.ZERO, null, message);
        }

        public static DebitResult failed(String message) {
            return new DebitResult("FAILED", BigDecimal.ZERO, null, message);
        }
    }
}

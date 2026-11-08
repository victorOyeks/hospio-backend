package com.hospio.payment.dto;

import com.hospio.common.enums.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Take payment (Front office 8.1-8.2, Bar 1.7, Kitchen 1.8, Laundry 1.6). Card and transfer go to
 * the caller's role's POS; cash is recorded on the spot. Split card + cash is two requests.
 */
public record PaymentRequestDto(@NotNull PaymentMethod method, @NotNull @Positive BigDecimal amount, String folioId, String bookingId,
                                String guestName, String guestPhone, String guestEmail, String description, String receiptPreference) {
}

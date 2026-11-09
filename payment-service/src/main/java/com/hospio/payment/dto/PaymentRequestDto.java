package com.hospio.payment.dto;

import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.Role;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Take payment (Front office 8.1-8.2, Bar 1.7, Kitchen 1.8, Laundry 1.6). Card and transfer go to
 * the caller's role's POS; cash is recorded on the spot. Split card + cash is two requests.
 * {@code orderId} is the kitchen or bar order this pays (fnb closes it when the payment is approved). {@code role} is the desk taking
 * the payment and must be one of the caller's roles; without it the first of FRONT_OFFICE, BAR, KITCHEN, LAUNDRY, MANAGER, OWNER the caller holds is used.
 */
public record PaymentRequestDto(@NotNull PaymentMethod method, @NotNull @Positive BigDecimal amount, String folioId, String bookingId,
                                String guestName, String guestPhone, String guestEmail, String description, String receiptPreference,
                                String orderId, Role role) {
    /** Without an order or role (a folio or booking payment). */
    public PaymentRequestDto(PaymentMethod method, BigDecimal amount, String folioId, String bookingId, String guestName, String guestPhone,
                             String guestEmail, String description, String receiptPreference) {
        this(method, amount, folioId, bookingId, guestName, guestPhone, guestEmail, description, receiptPreference, null, null);
    }
}

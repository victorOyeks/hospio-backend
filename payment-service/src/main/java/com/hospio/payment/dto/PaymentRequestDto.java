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
 * {@code terminalId} is the POS the desk picked for a card or transfer (one of this hotel's); without it, the POS assigned to the role.
 * {@code partId}: the part of a split bill this pays ({@code amount} at most what that part still owes). {@code tipAmount}: a tip on
 * top of {@code amount}, only for an order where the location records tips; the guest pays {@code amount + tipAmount} (that is what
 * goes to the POS and on the receipt) and only {@code amount} counts as the sale.
 */
public record PaymentRequestDto(@NotNull PaymentMethod method, @NotNull @Positive BigDecimal amount, String folioId, String bookingId,
                                String guestName, String guestPhone, String guestEmail, String description, String receiptPreference,
                                String orderId, Role role, String terminalId, String partId, @jakarta.validation.constraints.PositiveOrZero BigDecimal tipAmount) {
    /** Without a part of a split bill or a tip. */
    public PaymentRequestDto(PaymentMethod method, BigDecimal amount, String folioId, String bookingId, String guestName, String guestPhone,
                             String guestEmail, String description, String receiptPreference, String orderId, Role role, String terminalId) {
        this(method, amount, folioId, bookingId, guestName, guestPhone, guestEmail, description, receiptPreference, orderId, role, terminalId, null, null);
    }

    /** Without a picked terminal: the POS assigned to the role. */
    public PaymentRequestDto(PaymentMethod method, BigDecimal amount, String folioId, String bookingId, String guestName, String guestPhone,
                             String guestEmail, String description, String receiptPreference, String orderId, Role role) {
        this(method, amount, folioId, bookingId, guestName, guestPhone, guestEmail, description, receiptPreference, orderId, role, null);
    }

    /** Without an order or role (a folio or booking payment). */
    public PaymentRequestDto(PaymentMethod method, BigDecimal amount, String folioId, String bookingId, String guestName, String guestPhone,
                             String guestEmail, String description, String receiptPreference) {
        this(method, amount, folioId, bookingId, guestName, guestPhone, guestEmail, description, receiptPreference, null, null, null);
    }
}

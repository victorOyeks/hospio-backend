package com.hospio.payment.dto;

import com.hospio.payment.entity.PaymentRequest;
import com.hospio.payment.entity.Refund;
import java.math.BigDecimal;
import java.time.Instant;

/** A refund as the Payments screen lists it: the refund plus who paid and what was paid, so the screen needn't load every payment. */
public record RefundRow(String id, String paymentId, String folioId, BigDecimal amount, String reason, Refund.Status status, String requestedBy,
                        String approvedBy, String approvalId, Instant decidedAt, Instant createdAt, String hotelId, String guestName,
                        BigDecimal paymentAmount, String paymentMethod, String paymentReference) {

    public static RefundRow of(Refund r, PaymentRequest p) {
        return new RefundRow(r.getId(), r.getPaymentId(), r.getFolioId(), r.getAmount(), r.getReason(), r.getStatus(), r.getRequestedBy(),
                r.getApprovedBy(), r.getApprovalId(), r.getDecidedAt(), r.getCreatedAt(), r.getHotelId(), p == null ? null : p.getGuestName(),
                p == null ? null : p.getAmount(), p == null ? null : p.getMethod().name(), reference(r.getPaymentId()));
    }

    /** "PAY-1A2B3C": how the screens name a payment. */
    public static String reference(String paymentId) {
        return paymentId == null ? null : "PAY-" + paymentId.substring(0, Math.min(6, paymentId.length())).toUpperCase(java.util.Locale.ROOT);
    }
}

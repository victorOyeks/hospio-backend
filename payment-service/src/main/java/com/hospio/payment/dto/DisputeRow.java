package com.hospio.payment.dto;

import com.hospio.payment.entity.Dispute;
import com.hospio.payment.entity.PaymentRequest;
import java.math.BigDecimal;
import java.time.Instant;

/** A dispute as the Payments screen lists it, with the payment's guest, amount and reference. */
public record DisputeRow(String id, String paymentId, String reason, Dispute.Status status, String resolution, String openedBy,
                         String resolvedBy, Instant resolvedAt, Instant createdAt, String hotelId, String guestName, BigDecimal amount,
                         String paymentMethod, String paymentReference) {

    public static DisputeRow of(Dispute d, PaymentRequest p) {
        return new DisputeRow(d.getId(), d.getPaymentId(), d.getReason(), d.getStatus(), d.getResolution(), d.getOpenedBy(), d.getResolvedBy(),
                d.getResolvedAt(), d.getCreatedAt(), d.getHotelId(), p == null ? null : p.getGuestName(), p == null ? null : p.getAmount(),
                p == null ? null : p.getMethod().name(), RefundRow.reference(d.getPaymentId()));
    }
}

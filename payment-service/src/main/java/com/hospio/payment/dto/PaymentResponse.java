package com.hospio.payment.dto;

import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.enums.Role;
import com.hospio.payment.entity.PaymentRequest;
import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(String id, String folioId, String bookingId, String guestName, Role role, PaymentMethod method,
                              BigDecimal amount, String currency, PaymentStatus status, String terminalId, String providerRef,
                              String description, String failureReason, String orderId, String requestedBy, String requestedByName, Instant createdAt,
                              Instant resolvedAt, Instant verifiedAt, String hotelId) {
    public static PaymentResponse of(PaymentRequest p) {
        return new PaymentResponse(p.getId(), p.getFolioId(), p.getBookingId(), p.getGuestName(), p.getRole(), p.getMethod(), p.getAmount(),
                p.getCurrency(), p.getStatus(), p.getTerminalId(), p.getProviderRef(), p.getDescription(), p.getFailureReason(), p.getOrderId(),
                p.getRequestedBy(), p.getRequestedByName(), p.getCreatedAt(), p.getResolvedAt(), p.getVerifiedAt(), p.getHotelId());
    }
}

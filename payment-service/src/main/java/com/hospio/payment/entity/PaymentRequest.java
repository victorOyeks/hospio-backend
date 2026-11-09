package com.hospio.payment.entity;

import com.hospio.common.entity.TenantEntity;
import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** One attempt to take money: cash is approved on the spot, card and transfer go to the role's POS. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "payment_requests")
public class PaymentRequest extends TenantEntity {

    @Column(name = "folio_id", length = 36)
    private String folioId;

    @Column(name = "booking_id", length = 36)
    private String bookingId;

    /** The kitchen or bar order this payment is for. */
    @Column(name = "order_id", length = 36)
    private String orderId;

    @Column(name = "guest_name", length = 150)
    private String guestName;

    @Column(name = "guest_phone", length = 20)
    private String guestPhone;

    @Column(name = "guest_email")
    private String guestEmail;

    @Column(name = "requested_by", nullable = false, length = 36)
    private String requestedBy;

    @Column(name = "requested_by_name", length = 150)
    private String requestedByName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    @lombok.Builder.Default
    private String currency = "NGN";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "terminal_id", length = 36)
    private String terminalId;

    @Column(length = 40)
    private String provider;

    @Column(name = "provider_ref", length = 100)
    private String providerRef;

    @Column(length = 300)
    private String description;

    @Column(name = "receipt_preference", length = 20)
    private String receiptPreference;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    public boolean isPendingOrProcessing() {
        return status == PaymentStatus.PENDING || status == PaymentStatus.PROCESSING || status == PaymentStatus.NO_RESPONSE;
    }
}

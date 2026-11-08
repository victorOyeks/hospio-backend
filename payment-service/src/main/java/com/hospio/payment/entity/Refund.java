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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "refunds")
public class Refund extends TenantEntity {

    public enum Status { PENDING_APPROVAL, APPROVED, DECLINED }

    @Column(name = "payment_id", nullable = false, length = 36)
    private String paymentId;

    @Column(name = "folio_id", length = 36)
    private String folioId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 300)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.PENDING_APPROVAL;

    @Column(name = "requested_by", nullable = false, length = 36)
    private String requestedBy;

    @Column(name = "approved_by", length = 36)
    private String approvedBy;

    @Column(name = "approval_id", length = 36)
    private String approvalId;

    @Column(name = "decided_at")
    private Instant decidedAt;
}

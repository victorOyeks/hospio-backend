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
@Table(name = "disputes")
public class Dispute extends TenantEntity {

    public enum Status { OPEN, RESOLVED }

    @Column(name = "payment_id", nullable = false, length = 36)
    private String paymentId;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.OPEN;

    @Column(length = 500)
    private String resolution;

    @Column(name = "opened_by", length = 36)
    private String openedBy;

    @Column(name = "resolved_by", length = 36)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}

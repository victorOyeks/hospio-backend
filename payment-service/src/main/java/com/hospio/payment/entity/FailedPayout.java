package com.hospio.payment.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A settlement the bank bounced: why, how many times finance retried, and when it was finally resolved. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "failed_payouts")
public class FailedPayout extends TenantEntity {

    @Column(name = "settlement_id", nullable = false, length = 36)
    private String settlementId;

    @Column(name = "payout_batch_id", length = 36)
    private String payoutBatchId;

    @Column(name = "business_name", length = 150)
    private String businessName;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "bank_label", length = 150)
    private String bankLabel;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false)
    @lombok.Builder.Default
    private int attempts = 0;

    @Column(name = "last_tried_at")
    private Instant lastTriedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by", length = 36)
    private String resolvedBy;
}

package com.hospio.payment.entity;

import com.hospio.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A run of payouts to businesses: pending settlements bundled, sent to the bank, marked paid (or partly failed). Platform-only table. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "payout_batches")
public class PayoutBatch extends BaseEntity {

    public enum Status { PROCESSING, PAID, PART_FAILED }

    /** PB-YYYY-NNN, numbered per year. */
    @Column(nullable = false, length = 20, unique = true)
    private String ref;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.PROCESSING;

    @Column(nullable = false)
    private BigDecimal total;

    @Column(name = "business_count", nullable = false)
    private int businessCount;

    @Column(name = "settlement_count", nullable = false)
    private int settlementCount;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "paid_on")
    private LocalDate paidOn;

    @Column(name = "paid_reference", length = 100)
    private String paidReference;
}

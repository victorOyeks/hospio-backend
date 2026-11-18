package com.hospio.platform.entity;

import com.hospio.common.entity.TenantEntity;
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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "commissions")
public class Commission extends TenantEntity {

    @Column(name = "marketer_id", nullable = false, length = 36)
    private String marketerId;

    @Column(nullable = false, length = 7)
    private String month;

    @Column(name = "base_amount", nullable = false)
    private BigDecimal baseAmount;

    @Column(nullable = false)
    private BigDecimal gross;

    @Column(nullable = false)
    private BigDecimal wht;

    @Column(nullable = false)
    private BigDecimal net;

    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = "DUE";

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "profile_id", length = 36)
    private String profileId;

    @Column(name = "paid_reference", length = 100)
    private String paidReference;

    @Column(name = "paid_on")
    private LocalDate paidOn;

    /** DAILY_FEE (a collected daily fee) or CLAWBACK (a reversed or refunded one); null for the old monthly-statement rows. */
    @Column(length = 20)
    private String source;

    /** The daily fee the line comes from. */
    @Column(name = "source_id", length = 36)
    private String sourceId;

    /** The debit reference that collected it (or the refund's reference); unique per source, so a debit never earns twice. */
    @Column(name = "source_ref", length = 100)
    private String sourceRef;

    /** The day the fee was for (the 12-month window is checked against it). */
    @Column(name = "fee_date")
    private LocalDate feeDate;

    /** The Lagos day the money was collected (or given back); {@code month} is its month. */
    @Column(name = "earned_on")
    private LocalDate earnedOn;

    @Column(name = "share_pct")
    private BigDecimal sharePct;

    @Column(name = "wht_pct")
    private BigDecimal whtPct;

    /** The marketer statement the line is on; null until the next statement is made. */
    @Column(name = "statement_id", length = 36)
    private String statementId;

    @Column(length = 300)
    private String note;

    public static final String DAILY_FEE = "DAILY_FEE";
    public static final String CLAWBACK = "CLAWBACK";
    /** A line not on a statement yet. */
    public static final String ACCRUED = "ACCRUED";
    /** On a statement that is not paid yet. */
    public static final String ON_STATEMENT = "ON_STATEMENT";
}

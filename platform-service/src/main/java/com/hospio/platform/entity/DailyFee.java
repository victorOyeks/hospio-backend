package com.hospio.platform.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Hospio's fee for one hotel and one Lagos day (docs/plan-daily-fees.md). {@code due} is the day's fee plus what earlier days left
 * unpaid ({@code carriedIn}); {@code collected} is what the debit took.
 *
 * <p>Status: SHADOW (debit off: statement only, nothing owed), NO_FEE (nothing sold), SCHEDULED (debit at 06:00), PAID, PART_PAID
 * (the rest carried to the next day), UNPAID (nothing taken; carried), PENDING (the provider hasn't answered yet), CARRIED (its
 * remainder moved to a later day).
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "daily_fees")
public class DailyFee extends TenantEntity {

    public static final String SHADOW = "SHADOW";
    public static final String NO_FEE = "NO_FEE";
    public static final String SCHEDULED = "SCHEDULED";
    public static final String PENDING = "PENDING";
    public static final String PAID = "PAID";
    public static final String PART_PAID = "PART_PAID";
    public static final String UNPAID = "UNPAID";
    public static final String CARRIED = "CARRIED";

    @Column(name = "hotel_name", length = 150)
    private String hotelName;

    @Column(name = "fee_date", nullable = false)
    private LocalDate feeDate;

    @Column(name = "sales_total", nullable = false)
    private BigDecimal salesTotal;

    /** The services' lines as JSON (DailySales.Line[]). */
    @Column(name = "sales_lines", columnDefinition = "TEXT")
    private String salesLines;

    @Column(nullable = false)
    private BigDecimal rate;

    @Column(nullable = false)
    private BigDecimal fee;

    @Column(name = "carried_in", nullable = false)
    @lombok.Builder.Default
    private BigDecimal carriedIn = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal due;

    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal collected = BigDecimal.ZERO;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 20)
    private String provider;

    @Column(name = "debit_ref", length = 60)
    private String debitRef;

    @Column(nullable = false)
    @lombok.Builder.Default
    private int attempts = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "statement_sent_at")
    private Instant statementSentAt;

    @Column(name = "debited_at")
    private Instant debitedAt;

    public BigDecimal remaining() {
        return due.subtract(collected).max(BigDecimal.ZERO);
    }
}

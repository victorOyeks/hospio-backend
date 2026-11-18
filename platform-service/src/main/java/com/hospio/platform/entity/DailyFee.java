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
 * <p>Since 18 Nov 2026 nothing is debited: a day with a fee is ACCRUED, then INVOICED on its month's invoice, then PAID with it
 * ({@code collected} = what the invoice payments covered of it). The older statuses below are from the daily-debit trial.
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
    /** The fee is worked out and waits for the month's invoice (docs/plan-monthly-invoices.md). */
    public static final String ACCRUED = "ACCRUED";
    /** On a monthly invoice that is not paid yet. */
    public static final String INVOICED = "INVOICED";

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

    /** The hotel's TRANSACTION device package this day collects towards; null when none. */
    @Column(name = "device_package_id", length = 36)
    private String devicePackageId;

    /** The device share of sales (2.5 % by default); 0 without a package. */
    @Column(name = "device_rate", nullable = false)
    @lombok.Builder.Default
    private BigDecimal deviceRate = BigDecimal.ZERO;

    /** This day's device fee: deviceRate % of sales, capped at what is left of the package. Part of {@code due}. */
    @Column(name = "device_fee", nullable = false)
    @lombok.Builder.Default
    private BigDecimal deviceFee = BigDecimal.ZERO;

    /** Device fee left unpaid on earlier days, carried in. Part of {@code due}. */
    @Column(name = "device_carried_in", nullable = false)
    @lombok.Builder.Default
    private BigDecimal deviceCarriedIn = BigDecimal.ZERO;

    /** What Hospio360 gave back of the platform fee collected (a provider reversal or a refund); the marketer's share of it is clawed back. */
    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal refunded = BigDecimal.ZERO;

    @Column(name = "refund_note", length = 300)
    private String refundNote;

    /** The monthly invoice the day was billed on (docs/plan-monthly-invoices.md); null until the invoice for its month is issued. */
    @Column(name = "monthly_invoice_id", length = 36)
    private String monthlyInvoiceId;

    public BigDecimal remaining() {
        return due.subtract(collected).max(BigDecimal.ZERO);
    }

    /** The platform part of what is due: the fee and what earlier days left of it. Paid first. */
    public BigDecimal platformDue() {
        return fee.add(carriedIn);
    }

    /** The device part of what is due: this day's device fee and what earlier days left of theirs. Paid after the platform part. */
    public BigDecimal deviceDue() {
        return deviceFee.add(deviceCarriedIn);
    }

    /** What the collection so far paid of the platform part (it comes first). */
    public BigDecimal platformPaid() {
        return collected.min(platformDue()).max(BigDecimal.ZERO);
    }

    /** What the collection so far paid of the device part (after the platform part). */
    public BigDecimal devicePaid() {
        return collected.subtract(platformDue()).max(BigDecimal.ZERO).min(deviceDue());
    }

    public BigDecimal platformRemaining() {
        return platformDue().subtract(platformPaid()).max(BigDecimal.ZERO);
    }

    public BigDecimal deviceRemaining() {
        return deviceDue().subtract(devicePaid()).max(BigDecimal.ZERO);
    }
}

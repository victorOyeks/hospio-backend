package com.hospio.platform.entity;

import com.hospio.common.entity.BaseEntity;
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
 * A marketer's commission statement for one month (docs/plan-marketers.md), made on the 5th for the month before: the lines not on a
 * statement yet (collections and clawbacks), WHT, net, and what earlier statements under the minimum carried in.
 *
 * <p>DRAFT (the marketer has 7 days to query it) → QUERIED (a note to Hospio360) → APPROVED (finance) → PAID (bank transfer recorded,
 * the marketer is emailed). CARRIED_OVER when {@code payable} is under the minimum payout: it moves into the next statement's
 * {@code carriedIn}. An ENDED marketer's statements are never carried over while something is payable ({@code finalStatement}). Platform-only table, no business column.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "marketer_statements")
public class MarketerStatement extends BaseEntity {

    public static final String DRAFT = "DRAFT";
    public static final String QUERIED = "QUERIED";
    public static final String APPROVED = "APPROVED";
    public static final String PAID = "PAID";
    public static final String CARRIED_OVER = "CARRIED_OVER";

    @Column(name = "profile_id", nullable = false, length = 36)
    private String profileId;

    @Column(nullable = false, length = 7)
    private String month;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    /** Platform fee collected net of refunds on this statement's lines (the commission base). */
    @Column(name = "fees_collected", nullable = false)
    @lombok.Builder.Default
    private BigDecimal feesCollected = BigDecimal.ZERO;

    /** Gross commission on collections (positive lines). */
    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal gross = BigDecimal.ZERO;

    /** Gross clawed back on reversals and refunds (zero or negative). */
    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal clawback = BigDecimal.ZERO;

    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal wht = BigDecimal.ZERO;

    /** This month's lines after WHT: gross + clawback − WHT. */
    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal net = BigDecimal.ZERO;

    /** What earlier statements under the minimum carried into this one. */
    @Column(name = "carried_in", nullable = false)
    @lombok.Builder.Default
    private BigDecimal carriedIn = BigDecimal.ZERO;

    /** net + carriedIn: what is paid (or carried on when under the minimum). */
    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal payable = BigDecimal.ZERO;

    @Column(name = "minimum_payout", nullable = false)
    @lombok.Builder.Default
    private BigDecimal minimumPayout = BigDecimal.ZERO;

    /**
     * The ENDED marketer's last statement: every open line and carried-over statement, approved and paid whatever the amount (the
     * minimum payout doesn't apply).
     */
    @Column(name = "final_statement", nullable = false)
    @lombok.Builder.Default
    private boolean finalStatement = false;

    /** The statement this one's payable was carried into (CARRIED_OVER). */
    @Column(name = "carried_to_id", length = 36)
    private String carriedToId;

    @Column(name = "query_until")
    private Instant queryUntil;

    @Column(name = "query_note", length = 1000)
    private String queryNote;

    @Column(name = "queried_at")
    private Instant queriedAt;

    @Column(name = "query_answer", length = 1000)
    private String queryAnswer;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by", length = 36)
    private String approvedBy;

    @Column(name = "approved_by_name", length = 150)
    private String approvedByName;

    @Column(name = "paid_amount")
    private BigDecimal paidAmount;

    @Column(name = "paid_reference", length = 100)
    private String paidReference;

    @Column(name = "paid_on")
    private LocalDate paidOn;

    @Column(name = "paid_recorded_at")
    private Instant paidRecordedAt;

    @Column(name = "paid_recorded_by", length = 36)
    private String paidRecordedBy;

    @Column(name = "paid_recorded_name", length = 150)
    private String paidRecordedName;

    @Column(name = "paid_email_sent_at")
    private Instant paidEmailSentAt;

    /** The bank details the statement is paid to, copied when it is approved. */
    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(name = "bank_account", length = 30)
    private String bankAccount;

    @Column(name = "bank_account_name", length = 150)
    private String bankAccountName;
}

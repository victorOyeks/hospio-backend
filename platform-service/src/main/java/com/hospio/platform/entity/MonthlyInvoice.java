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
 * A business's Hospio360 invoice for one month (docs/plan-monthly-invoices.md): the platform fee per location, guest messages, devices
 * and internet, what earlier months carried in, credits and adjustments. Issued on the 1st, due 7 days later, 3 days' grace.
 *
 * <p>Status: DUE, OVERDUE (past its due date), PAID, CARRIED_FORWARD (under the minimum: rolled into the next month's invoice, which pays
 * it), VOID (cancelled; a reissue replaces it).
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "monthly_invoices")
public class MonthlyInvoice extends TenantEntity {

    public static final String DUE = "DUE";
    public static final String OVERDUE = "OVERDUE";
    public static final String PAID = "PAID";
    public static final String CARRIED_FORWARD = "CARRIED_FORWARD";
    public static final String VOID = "VOID";

    @Column(name = "business_name", length = 200)
    private String businessName;

    @Column(nullable = false, length = 30, unique = true)
    private String number;

    @Column(nullable = false, length = 7)
    private String month;

    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Column(name = "grace_until", nullable = false)
    private LocalDate graceUntil;

    @Column(name = "platform_total", nullable = false)
    @lombok.Builder.Default
    private BigDecimal platformTotal = BigDecimal.ZERO;

    @Column(name = "messages_total", nullable = false)
    @lombok.Builder.Default
    private BigDecimal messagesTotal = BigDecimal.ZERO;

    @Column(name = "devices_total", nullable = false)
    @lombok.Builder.Default
    private BigDecimal devicesTotal = BigDecimal.ZERO;

    @Column(name = "carried_in_total", nullable = false)
    @lombok.Builder.Default
    private BigDecimal carriedInTotal = BigDecimal.ZERO;

    /** Credit notes (negative) and other adjustments added after issue. */
    @Column(name = "adjustments_total", nullable = false)
    @lombok.Builder.Default
    private BigDecimal adjustmentsTotal = BigDecimal.ZERO;

    @Column(name = "vat_pct", nullable = false)
    @lombok.Builder.Default
    private BigDecimal vatPct = BigDecimal.ZERO;

    @Column(name = "vat_amount", nullable = false)
    @lombok.Builder.Default
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "paid_amount", nullable = false)
    @lombok.Builder.Default
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(nullable = false, length = 20)
    private String status;

    /** CARRIED_FORWARD: the invoice it rolled into. */
    @Column(name = "carried_into_id", length = 36)
    private String carriedIntoId;

    @Column(name = "paid_at")
    private Instant paidAt;

    /** PAYSTACK, BANK_TRANSFER, CASH, OTHER, or CARRIED (paid with the invoice it rolled into). */
    @Column(name = "paid_via", length = 30)
    private String paidVia;

    @Column(name = "paystack_ref", length = 80)
    private String paystackRef;

    /** A one-time extension: the restriction starts the morning after this day instead of after the grace period. */
    @Column(name = "extended_until")
    private LocalDate extendedUntil;

    @Column(name = "extension_reason", length = 300)
    private String extensionReason;

    @Column(name = "extended_by_name", length = 150)
    private String extendedByName;

    /** When the restriction started (06:00 the morning after the last grace day); null while not restricted. */
    @Column(name = "restricted_at")
    private Instant restrictedAt;

    @Column(name = "restriction_lifted_at")
    private Instant restrictionLiftedAt;

    @Column(name = "void_reason", length = 300)
    private String voidReason;

    @Column(name = "reissue_of_id", length = 36)
    private String reissueOfId;

    @Column(name = "replaced_by_id", length = 36)
    private String replacedById;

    public boolean open() {
        return DUE.equals(status) || OVERDUE.equals(status);
    }

    public BigDecimal outstanding() {
        return total.subtract(paidAmount).max(BigDecimal.ZERO);
    }

    /** The last day before the restriction: the extension when one was granted, else the end of the grace period. */
    public LocalDate lastDay() {
        return extendedUntil != null ? extendedUntil : graceUntil;
    }

    /** True while the restriction is on (started, not lifted). */
    public boolean restricted() {
        return restrictedAt != null && restrictionLiftedAt == null && open();
    }
}

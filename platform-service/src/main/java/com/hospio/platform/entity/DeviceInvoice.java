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
 * A monthly device invoice (docs/plan-device-packages.md): INSTALMENT {@code sequence} of {@code ofCount} of a MONTHLY package, or
 * the DATA_PLAN for a month. DUE → PAID (debit, Paystack or by hand); DUE past its date → OVERDUE.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "device_invoices")
public class DeviceInvoice extends TenantEntity {

    public static final String INSTALMENT = "INSTALMENT";
    public static final String DATA_PLAN = "DATA_PLAN";
    public static final String DUE = "DUE";
    public static final String OVERDUE = "OVERDUE";
    public static final String PAID = "PAID";
    public static final String VOID = "VOID";

    @Column(name = "package_id", nullable = false, length = 36)
    private String packageId;

    @Column(name = "hotel_name", length = 150)
    private String hotelName;

    @Column(nullable = false, length = 40, unique = true)
    private String number;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false)
    private int sequence;

    @Column(name = "of_count")
    private Integer ofCount;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "paid_amount", nullable = false)
    @lombok.Builder.Default
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "paid_at")
    private Instant paidAt;

    /** DEBIT, PAYSTACK or MANUAL. */
    @Column(name = "paid_via", length = 20)
    private String paidVia;

    @Column(name = "paid_reference", length = 80)
    private String paidReference;

    @Column(name = "debit_ref", length = 60)
    private String debitRef;

    @Column(name = "debit_attempts", nullable = false)
    @lombok.Builder.Default
    private int debitAttempts = 0;

    @Column(name = "last_debit_on")
    private LocalDate lastDebitOn;

    @Column(name = "paystack_ref", length = 80)
    private String paystackRef;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "reminded_at")
    private Instant remindedAt;

    @Column(name = "flagged_at")
    private Instant flaggedAt;

    @Column(name = "paused_at")
    private Instant pausedAt;

    public boolean open() {
        return DUE.equals(status) || OVERDUE.equals(status);
    }
}

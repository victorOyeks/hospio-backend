package com.hospio.platform.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A notice sent about a monthly invoice, or an action on it: the reminder log and the audit trail. Notices (ISSUED, REMINDER, DUE_TODAY,
 * OVERDUE, FINAL_WARNING, RESTRICTED) are unique per invoice, kind and Lagos day, so a rerun never repeats one.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "monthly_invoice_events")
public class MonthlyInvoiceEvent extends TenantEntity {

    public static final String ISSUED = "ISSUED";
    public static final String REMINDER = "REMINDER";
    public static final String DUE_TODAY = "DUE_TODAY";
    public static final String OVERDUE = "OVERDUE";
    public static final String FINAL_WARNING = "FINAL_WARNING";
    public static final String RESTRICTED = "RESTRICTED";
    public static final String LIFTED = "LIFTED";
    public static final String PAID = "PAID";
    public static final String PAYMENT = "PAYMENT";
    public static final String EXTENDED = "EXTENDED";
    public static final String ADJUSTED = "ADJUSTED";
    public static final String VOIDED = "VOIDED";
    public static final String REISSUED = "REISSUED";
    public static final String CARRIED_FORWARD = "CARRIED_FORWARD";

    @Column(name = "invoice_id", nullable = false, length = 36)
    private String invoiceId;

    @Column(nullable = false, length = 30)
    private String kind;

    @Column(name = "on_date", nullable = false)
    private LocalDate onDate;

    @Column(length = 200)
    private String recipient;

    /** For a notice: EMAILED, SKIPPED_QA_ONLY (staging guard), NO_EMAIL; for an action: null. */
    @Column(length = 40)
    private String outcome;

    @Column(length = 500)
    private String note;

    @Column(name = "actor_name", length = 150)
    private String actorName;
}

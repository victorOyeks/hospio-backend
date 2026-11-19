package com.hospio.messaging.billing;

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

/** One hotel's guest-messages invoice for one month (docs/guest-message-billing.md): counts and amounts per channel. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "comm_invoices")
public class CommInvoice extends TenantEntity {

    public enum Status { DUE, OVERDUE, PAID, VOID }

    @Column(nullable = false, length = 30)
    private String number;

    @Column(nullable = false, length = 7)
    private String month;

    @Column(name = "hotel_name", length = 200)
    private String hotelName;

    @Column(name = "email_count", nullable = false)
    private int emailCount;

    /** Historical only: guest messages no longer go by SMS, so this is 0 on every new invoice; an older one keeps what it charged. */
    @Column(name = "sms_count", nullable = false)
    private int smsCount;

    @Column(name = "whatsapp_count", nullable = false)
    private int whatsappCount;

    @Column(name = "email_amount", nullable = false)
    private BigDecimal emailAmount;

    /** Historical only, as {@link #smsCount}. */
    @Column(name = "sms_amount", nullable = false)
    private BigDecimal smsAmount;

    @Column(name = "whatsapp_amount", nullable = false)
    private BigDecimal whatsappAmount;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 20)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "paid_via", length = 20)
    private String paidVia;

    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    @Column(name = "paid_by_name", length = 150)
    private String paidByName;

    @Column(length = 300)
    private String note;

    /** The Hospio360 monthly invoice (HIN-…) this is billed on (docs/plan-monthly-invoices.md); null while it stands alone. */
    @Column(name = "monthly_invoice_id", length = 36)
    private String monthlyInvoiceId;

    @Column(name = "monthly_invoice_number", length = 30)
    private String monthlyInvoiceNumber;
}

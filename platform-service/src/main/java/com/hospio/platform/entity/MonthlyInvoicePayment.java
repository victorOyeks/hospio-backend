package com.hospio.platform.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A payment against a monthly invoice (Paystack, or recorded by Hospio360), with its receipt number (RCP-YYYYMM-0001). */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "monthly_invoice_payments")
public class MonthlyInvoicePayment extends TenantEntity {

    @Column(name = "invoice_id", nullable = false, length = 36)
    private String invoiceId;

    @Column(name = "receipt_number", nullable = false, length = 30, unique = true)
    private String receiptNumber;

    @Column(nullable = false)
    private BigDecimal amount;

    /** PAYSTACK, BANK_TRANSFER, CASH, OTHER. */
    @Column(nullable = false, length = 30)
    private String via;

    @Column(nullable = false, length = 100)
    private String reference;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Column(name = "recorded_by_name", length = 150)
    private String recordedByName;

    @Column(length = 300)
    private String note;
}

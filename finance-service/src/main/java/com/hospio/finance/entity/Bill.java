package com.hospio.finance.entity;

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

/**
 * A bill to pay (supplier, utility): due on a date, maybe every week or month. Paying it records a paid expense. A supplier bill made from
 * a delivery received in Inventory carries the delivery (one bill per delivery), its order, the supplier and the supplier's invoice number.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "bills")
public class Bill extends TenantEntity {

    public enum Status { UNPAID, PAID, CANCELLED }

    public enum Repeats { NONE, WEEKLY, MONTHLY }

    @Column(nullable = false, length = 150)
    private String payee;

    @Column(nullable = false, length = 60)
    private String category;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @lombok.Builder.Default
    private Repeats repeats = Repeats.NONE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    @lombok.Builder.Default
    private Status status = Status.UNPAID;

    @Column(length = 500)
    private String note;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "paid_by", length = 36)
    private String paidBy;

    @Column(name = "paid_by_name", length = 150)
    private String paidByName;

    @Column(name = "paid_from", length = 20)
    private String paidFrom;

    @Column(name = "expense_id", length = 36)
    private String expenseId;

    @Column(name = "next_bill_id", length = 36)
    private String nextBillId;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    @Column(name = "supplier_id", length = 36)
    private String supplierId;

    /** The delivery this bill was made from; unique, so a replayed delivery never makes a second bill. */
    @Column(name = "delivery_id", length = 36)
    private String deliveryId;

    @Column(name = "purchase_order_id", length = 36)
    private String purchaseOrderId;

    @Column(name = "invoice_number", length = 60)
    private String invoiceNumber;
}

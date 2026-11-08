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
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** An expense: recorded, or over the manager's limit → waiting for the owner. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "expenses")
public class Expense extends TenantEntity {

    public enum Status { RECORDED, PENDING_APPROVAL, APPROVED, DECLINED, QUESTION, PAID }

    @Column(nullable = false, length = 60)
    private String category;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "paid_from", length = 20)
    private String paidFrom;

    @Column(name = "receipt_key")
    private String receiptKey;

    @Column(name = "expense_date", nullable = false)
    @lombok.Builder.Default
    private LocalDate expenseDate = LocalDate.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.RECORDED;

    @Column(name = "approval_id", unique = true, length = 36)
    private String approvalId;

    @Column(name = "requested_by", length = 36)
    private String requestedBy;

    @Column(name = "requested_by_name", length = 150)
    private String requestedByName;

    @Column(name = "decided_by", length = 36)
    private String decidedBy;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;
}

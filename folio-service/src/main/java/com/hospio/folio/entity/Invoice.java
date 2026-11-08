package com.hospio.folio.entity;

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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "invoices")
public class Invoice extends TenantEntity {

    public enum Status { DRAFT, SENT, PART_PAID, PAID, OVERDUE }

    @Column(name = "organization_id", nullable = false, length = 36)
    private String organizationId;

    @Column(nullable = false, unique = true, length = 20)
    private String number;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "paid_amount", nullable = false)
    @lombok.Builder.Default
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @lombok.Builder.Default
    private Status status = Status.DRAFT;

    @Column(name = "pdf_key")
    private String pdfKey;

    @Column(name = "sent_at")
    private Instant sentAt;
}

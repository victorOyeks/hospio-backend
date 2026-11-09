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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "till_entries")
public class TillEntry extends TenantEntity {

    /** PAYMENT is cash taken against a folio; PAY_IN and PAY_OUT are manual movements; REFUND and EXPENSE take cash out. */
    public enum Kind { PAYMENT, PAY_IN, PAY_OUT, REFUND, EXPENSE }

    @Column(name = "till_id", nullable = false, length = 36)
    private String tillId;

    @Column(name = "payment_id", unique = true, length = 36)
    private String paymentId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String method;

    @Column(length = 300)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Kind kind = Kind.PAYMENT;

    /** What the entry is about: the refund or expense id, so a replayed event never posts twice. */
    @Column(length = 80)
    private String ref;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;
}

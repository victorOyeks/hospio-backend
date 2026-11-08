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

/** One approved payment in the ledger. Today's money, the P&L and Match a payment read these. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "revenue_entries")
public class RevenueEntry extends TenantEntity {

    @Column(name = "payment_id", nullable = false, unique = true, length = 36)
    private String paymentId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String method;

    @Column(length = 30)
    private String role;

    @Column(name = "folio_id", length = 36)
    private String folioId;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean matched = true;
}

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

/** A role's cash drawer for a shift: float in, cash payments in, counted and closed. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tills")
public class Till extends TenantEntity {

    public enum Status { OPEN, CLOSED }

    @Column(nullable = false, length = 30)
    private String role;

    @Column(name = "opened_by", nullable = false, length = 36)
    private String openedBy;

    @Column(name = "opened_by_name", length = 150)
    private String openedByName;

    @Column(name = "opened_at", nullable = false)
    @lombok.Builder.Default
    private Instant openedAt = Instant.now();

    @Column(name = "opening_float", nullable = false)
    @lombok.Builder.Default
    private BigDecimal openingFloat = BigDecimal.ZERO;

    @Column(name = "cash_in", nullable = false)
    @lombok.Builder.Default
    private BigDecimal cashIn = BigDecimal.ZERO;

    @Column(name = "expected_cash", nullable = false)
    @lombok.Builder.Default
    private BigDecimal expectedCash = BigDecimal.ZERO;

    @Column(name = "counted_cash")
    private BigDecimal countedCash;

    private BigDecimal variance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private Status status = Status.OPEN;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by", length = 36)
    private String closedBy;

    @Column(length = 500)
    private String note;
}

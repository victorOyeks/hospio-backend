package com.hospio.platform.entity;

import com.hospio.common.entity.TenantEntity;
import com.hospio.common.enums.ApprovalStatus;
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
@Table(name = "fee_statements")
public class FeeStatement extends TenantEntity {

    @Column(nullable = false, length = 7)
    private String month;

    @Column(name = "base_amount", nullable = false)
    private BigDecimal baseAmount;

    @Column(name = "fee_amount", nullable = false)
    private BigDecimal feeAmount;

    @Column(name = "paid_amount", nullable = false)
    @lombok.Builder.Default
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = "DUE";
}

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

/** Money received against a fee statement, as recorded by finance. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "fee_payments")
public class FeePayment extends TenantEntity {

    @Column(name = "statement_id", nullable = false, length = 36)
    private String statementId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Column(length = 100)
    private String reference;

    @Column(name = "recorded_by", length = 36)
    private String recordedBy;

    @Column(name = "recorded_by_name", length = 150)
    private String recordedByName;
}

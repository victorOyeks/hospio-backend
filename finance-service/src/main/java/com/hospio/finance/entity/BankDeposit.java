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
@Table(name = "bank_deposits")
public class BankDeposit extends TenantEntity {

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "bank_account", length = 100)
    private String bankAccount;

    @Column(length = 80)
    private String reference;

    @Column(name = "banked_by", length = 36)
    private String bankedBy;

    @Column(name = "banked_at", nullable = false)
    @lombok.Builder.Default
    private Instant bankedAt = Instant.now();

    @Column(name = "banked_by_name", length = 150)
    private String bankedByName;
}

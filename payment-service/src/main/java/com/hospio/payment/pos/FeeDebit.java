package com.hospio.payment.pos;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A fee debit run for platform-service (one per reference). Status: PENDING, APPROVED, FAILED. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "fee_debits")
public class FeeDebit extends TenantEntity {

    @Column(nullable = false, length = 60)
    private String reference;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "provider_ref", length = 100)
    private String providerRef;

    @Column(length = 500)
    private String message;
}

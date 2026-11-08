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

/** A company billed later for its staff's stays. Credit over ₦1M is owner-only. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "organizations")
public class Organization extends TenantEntity {

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "contact_name", length = 150)
    private String contactName;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "contact_email")
    private String contactEmail;

    private String address;

    @Column(name = "credit_limit", nullable = false)
    @lombok.Builder.Default
    private BigDecimal creditLimit = BigDecimal.ZERO;

    @Column(name = "daily_cap")
    private BigDecimal dailyCap;

    @Column(name = "billing_cycle", nullable = false, length = 20)
    @lombok.Builder.Default
    private String billingCycle = "MONTHLY";

    @Column(name = "payment_terms_days", nullable = false)
    @lombok.Builder.Default
    private int paymentTermsDays = 30;

    @Column(name = "agreement_notes", length = 2000)
    private String agreementNotes;

    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = "ACTIVE";
}

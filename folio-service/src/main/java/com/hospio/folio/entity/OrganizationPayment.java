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
@Table(name = "organization_payments")
public class OrganizationPayment extends TenantEntity {

    @Column(name = "organization_id", nullable = false, length = 36)
    private String organizationId;

    @Column(name = "invoice_id", length = 36)
    private String invoiceId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String method;

    @Column(length = 80)
    private String reference;

    @Column(name = "received_at", nullable = false)
    @lombok.Builder.Default
    private Instant receivedAt = Instant.now();

    @Column(name = "recorded_by", length = 36)
    private String recordedBy;
}

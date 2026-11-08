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

/** A discount above the manager's limit, waiting for the owner's decision. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pending_discounts")
public class PendingDiscount extends TenantEntity {

    @Column(name = "folio_id", nullable = false, length = 36)
    private String folioId;

    @Column(name = "approval_id", nullable = false, unique = true, length = 36)
    private String approvalId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 300)
    private String reason;

    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = "PENDING";
}

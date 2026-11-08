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

/** One line on a folio. Amounts are positive; the kind says which way they count. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "folio_lines")
public class FolioLine extends TenantEntity {

    public enum Kind { CHARGE, PAYMENT, DISCOUNT, REFUND }

    @Column(name = "folio_id", nullable = false, length = 36)
    private String folioId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(name = "source_module", length = 40)
    private String sourceModule;

    @Column(name = "source_ref", length = 80)
    private String sourceRef;

    @Column(length = 60)
    private String category;

    @Column(nullable = false, length = 300)
    private String description;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "tax_amount", nullable = false)
    @lombok.Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "posted_by", length = 36)
    private String postedBy;

    @Column(name = "posted_by_name", length = 150)
    private String postedByName;

    @Column(name = "posted_at", nullable = false)
    @lombok.Builder.Default
    private Instant postedAt = Instant.now();

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean voided = false;

    @Column(name = "void_reason", length = 300)
    private String voidReason;

    @Column(name = "voided_by", length = 36)
    private String voidedBy;

    @Column(name = "moved_from", length = 36)
    private String movedFrom;
}

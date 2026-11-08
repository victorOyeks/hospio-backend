package com.hospio.housekeeping.entity;

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

/** Damaged linen waiting for a supervisor's write-off. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "damaged_linen")
public class DamagedLinen extends TenantEntity {

    @Column(nullable = false, length = 100)
    private String item;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, length = 300)
    private String reason;

    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = "REPORTED";

    @Column(name = "approval_id", unique = true, length = 36)
    private String approvalId;

    @Column(name = "reported_by", length = 36)
    private String reportedBy;
}

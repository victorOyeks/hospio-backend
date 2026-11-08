package com.hospio.maintenance.entity;

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

/** Parts a technician needs for a repair; with a cost it goes through Approvals. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "part_requests")
public class PartRequest extends TenantEntity {

    @Column(name = "repair_id", length = 36)
    private String repairId;

    @Column(name = "part_name", nullable = false, length = 150)
    private String partName;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "estimated_cost")
    private BigDecimal estimatedCost;

    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = "REQUESTED";

    @Column(name = "approval_id", unique = true, length = 36)
    private String approvalId;

    @Column(name = "requested_by", length = 36)
    private String requestedBy;
}

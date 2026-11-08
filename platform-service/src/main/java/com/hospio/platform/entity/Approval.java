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

/** Something waiting for a manager or the owner: a spend, a discount, a refund, a price change. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "approvals")
public class Approval extends TenantEntity {

    @Column(name = "source_module", nullable = false, length = 40)
    private String sourceModule;

    @Column(name = "source_ref", length = 80)
    private String sourceRef;

    @Column(nullable = false, length = 40)
    private String kind;

    private BigDecimal amount;

    @Column(name = "requester_id", length = 36)
    private String requesterId;

    @Column(name = "requester_name", length = 150)
    private String requesterName;

    @Column(name = "approver_level", nullable = false, length = 20)
    private String approverLevel;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private ApprovalStatus status = ApprovalStatus.PENDING;

    @Column(name = "decided_by", length = 36)
    private String decidedBy;

    @Column(name = "decided_by_name", length = 150)
    private String decidedByName;

    @Column(length = 500)
    private String note;

    @Column(name = "decided_at")
    private Instant decidedAt;
}

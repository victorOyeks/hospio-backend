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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "reports")
public class Report extends TenantEntity {

    @Column(nullable = false, length = 40)
    private String kind;

    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = "READY";

    @Column(name = "storage_key")
    private String storageKey;

    @Column(length = 20)
    private String schedule;

    @Column(length = 500)
    private String recipients;

    @Column(name = "requested_by", length = 36)
    private String requestedBy;
}

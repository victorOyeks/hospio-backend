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
@Table(name = "commissions")
public class Commission extends TenantEntity {

    @Column(name = "marketer_id", nullable = false, length = 36)
    private String marketerId;

    @Column(nullable = false, length = 7)
    private String month;

    @Column(name = "base_amount", nullable = false)
    private BigDecimal baseAmount;

    @Column(nullable = false)
    private BigDecimal gross;

    @Column(nullable = false)
    private BigDecimal wht;

    @Column(nullable = false)
    private BigDecimal net;

    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = "DUE";

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "profile_id", length = 36)
    private String profileId;

    @Column(name = "paid_reference", length = 100)
    private String paidReference;

    @Column(name = "paid_on")
    private LocalDate paidOn;
}

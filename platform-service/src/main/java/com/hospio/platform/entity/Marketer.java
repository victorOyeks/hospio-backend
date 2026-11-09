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

/** A marketer who referred this business: 20% of H.M.S's share for 24 months, 5% WHT, paid monthly. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "marketers")
public class Marketer extends TenantEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "share_pct", nullable = false)
    @lombok.Builder.Default
    private BigDecimal sharePct = new BigDecimal("20");

    @Column(nullable = false)
    @lombok.Builder.Default
    private int months = 24;

    @Column(name = "wht_pct", nullable = false)
    @lombok.Builder.Default
    private BigDecimal whtPct = new BigDecimal("5");

    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = "PENDING";

    @Column(name = "started_on")
    private LocalDate startedOn;

    @Column(name = "ended_on")
    private LocalDate endedOn;

    /** The person behind this agreement ({@link MarketerProfile}); null for agreements made before profiles existed. */
    @Column(name = "profile_id", length = 36)
    private String profileId;

    /** The agreement still earns in {@code month} (yyyy-MM) when active and within its months from the start. */
    public boolean earnsIn(java.time.YearMonth month) {
        if (!"ACTIVE".equals(status) || startedOn == null) {
            return false;
        }
        java.time.YearMonth start = java.time.YearMonth.from(startedOn);
        return !month.isBefore(start) && month.isBefore(start.plusMonths(months));
    }
}

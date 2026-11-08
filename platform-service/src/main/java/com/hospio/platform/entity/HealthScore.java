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

/** L7 · How healthy a business looks: adoption, activity, money, support load. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "health_scores")
public class HealthScore extends TenantEntity {

    @Column(nullable = false)
    private int score;

    @Column(columnDefinition = "text")
    private String factors;

    @Column(name = "computed_at", nullable = false)
    @lombok.Builder.Default
    private Instant computedAt = Instant.now();
}

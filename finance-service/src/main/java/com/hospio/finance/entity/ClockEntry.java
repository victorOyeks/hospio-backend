package com.hospio.finance.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
@Table(name = "clock_entries")
public class ClockEntry extends TenantEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "user_name", length = 150)
    private String userName;

    @Column(name = "clock_in", nullable = false)
    @lombok.Builder.Default
    private Instant clockIn = Instant.now();

    @Column(name = "clock_out")
    private Instant clockOut;

    @Column(length = 300)
    private String note;

    /** When the person and the managers were reminded this entry was never clocked out (once). */
    @Column(name = "reminded_at")
    private java.time.Instant remindedAt;
}

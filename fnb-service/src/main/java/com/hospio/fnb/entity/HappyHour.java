package com.hospio.fnb.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
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
@Table(name = "happy_hours")
public class HappyHour extends TenantEntity {

    @Column(name = "outlet_id", nullable = false, length = 36)
    private String outletId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "from_time", nullable = false)
    private LocalTime fromTime;

    @Column(name = "to_time", nullable = false)
    private LocalTime toTime;

    /** Comma-separated day names, e.g. MON,TUE,FRI. */
    @Column(nullable = false, length = 60)
    private String days;

    @Column(name = "discount_pct", nullable = false)
    private BigDecimal discountPct;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;
}

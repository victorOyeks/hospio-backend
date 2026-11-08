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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "linen_movements")
public class LinenMovement extends TenantEntity {

    public enum Direction { IN, OUT }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Direction direction;

    @Column(nullable = false, length = 100)
    private String item;

    @Column(nullable = false)
    private int quantity;

    @Column(length = 300)
    private String note;

    @Column(name = "recorded_by", length = 36)
    private String recordedBy;
}

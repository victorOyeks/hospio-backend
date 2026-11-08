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
@Table(name = "tables")
public class DiningTable extends TenantEntity {

    @Column(name = "outlet_id", nullable = false, length = 36)
    private String outletId;

    @Column(nullable = false, length = 20)
    private String number;

    @Column(nullable = false)
    @lombok.Builder.Default
    private int seats = 4;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;
}

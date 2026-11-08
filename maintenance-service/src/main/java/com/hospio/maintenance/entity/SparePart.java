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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "spare_parts")
public class SparePart extends TenantEntity {

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false)
    @lombok.Builder.Default
    private int quantity = 0;

    @Column(name = "min_quantity", nullable = false)
    @lombok.Builder.Default
    private int minQuantity = 0;

    @Column(name = "unit_cost")
    private BigDecimal unitCost;
}

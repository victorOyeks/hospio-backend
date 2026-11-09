package com.hospio.inventory.entity;

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
@Table(name = "stock_items")
public class StockItem extends TenantEntity {

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 60)
    private String category;

    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String unit = "pcs";

    @Column(name = "min_level", nullable = false)
    @lombok.Builder.Default
    private BigDecimal minLevel = BigDecimal.ZERO;

    @Column(name = "unit_cost")
    private BigDecimal unitCost;

    @Column(name = "supplier_id", length = 36)
    private String supplierId;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;

    @Column(name = "par_level", nullable = false)
    @lombok.Builder.Default
    private BigDecimal parLevel = BigDecimal.ZERO;
}

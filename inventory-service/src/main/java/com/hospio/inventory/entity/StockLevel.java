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
@Table(name = "stock_levels")
public class StockLevel extends TenantEntity {

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "store_id", nullable = false, length = 36)
    private String storeId;

    @Column(nullable = false)
    @lombok.Builder.Default
    private BigDecimal quantity = BigDecimal.ZERO;
}

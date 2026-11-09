package com.hospio.housekeeping.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** One line of the guest laundry price list, with its express price. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "laundry_prices")
public class LaundryPrice extends TenantEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private String category = "WASH_IRON";

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "express_price", nullable = false)
    private BigDecimal expressPrice;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    @lombok.Builder.Default
    private int sortOrder = 0;
}

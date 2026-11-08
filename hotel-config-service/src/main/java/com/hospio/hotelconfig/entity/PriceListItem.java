package com.hospio.hotelconfig.entity;

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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "price_list_items")
public class PriceListItem extends TenantEntity {

    @Column(name = "price_list_id", nullable = false, length = 36)
    private String priceListId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "tax_rate")
    private BigDecimal taxRate;

    @Column(name = "charge_category", length = 60)
    private String chargeCategory;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;
}

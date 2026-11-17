package com.hospio.platform.entity;

import com.hospio.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A standard item Hospio360 supplies (Settings · Device catalogue): ONE_OFF (a tablet, a router) or MONTHLY (a data plan). Platform-only. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "device_catalogue")
public class DeviceCatalogueItem extends BaseEntity {

    @Column(nullable = false, length = 120)
    private String name;

    /** ONE_OFF or MONTHLY. */
    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    @lombok.Builder.Default
    private int sortOrder = 0;
}

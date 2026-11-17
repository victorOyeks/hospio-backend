package com.hospio.platform.entity;

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

/** One line of a device package: a catalogue item (or a custom one) with its quantity and the price agreed for this hotel. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "device_package_items")
public class DevicePackageItem extends TenantEntity {

    @Column(name = "package_id", nullable = false, length = 36)
    private String packageId;

    @Column(name = "catalogue_id", length = 36)
    private String catalogueId;

    @Column(nullable = false, length = 120)
    private String name;

    /** ONE_OFF or MONTHLY. */
    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false)
    private BigDecimal lineTotal;
}

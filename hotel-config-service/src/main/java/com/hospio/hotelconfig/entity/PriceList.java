package com.hospio.hotelconfig.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A price list by kind: MINIBAR, LAUNDRY, EXTRAS, LATE_CHECKOUT, RENTALS. One per hotel per kind. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "price_lists")
public class PriceList extends TenantEntity {

    @Column(nullable = false, length = 40)
    private String kind;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;
}

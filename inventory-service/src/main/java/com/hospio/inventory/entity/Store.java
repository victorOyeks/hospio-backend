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

/** Where stock is kept: the main store, the kitchen store, the bar store, housekeeping's cupboard. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "stores")
public class Store extends TenantEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 30)
    @lombok.Builder.Default
    private String kind = "MAIN";

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;
}

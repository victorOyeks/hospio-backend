package com.hospio.property.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A floor, wing or public area (lobby, pool, gym) that rooms and tasks belong to. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "areas")
public class Area extends TenantEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 30)
    @lombok.Builder.Default
    private String kind = "FLOOR";

    @Column(name = "sort_order", nullable = false)
    @lombok.Builder.Default
    private int sortOrder = 0;
}

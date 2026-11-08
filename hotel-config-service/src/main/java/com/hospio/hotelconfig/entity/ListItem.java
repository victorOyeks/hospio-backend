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

/** An entry in one of the hotel's lists (cancellation reasons, charge categories, job titles, areas...). */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "list_items")
public class ListItem extends TenantEntity {

    @Column(name = "list_name", nullable = false, length = 60)
    private String listName;

    @Column(length = 60)
    private String code;

    @Column(nullable = false, length = 150)
    private String label;

    @Column(name = "sort_order", nullable = false)
    @lombok.Builder.Default
    private int sortOrder = 0;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;
}

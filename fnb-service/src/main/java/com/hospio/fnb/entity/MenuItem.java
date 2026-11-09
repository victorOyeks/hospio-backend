package com.hospio.fnb.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A dish or drink. {@code options} is JSON for choices (size, extras); {@code available} is the sold-out toggle. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "menu_items")
public class MenuItem extends TenantEntity {

    @Column(name = "outlet_id", nullable = false, length = 36)
    private String outletId;

    @Column(nullable = false, length = 60)
    private String category;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "tax_rate")
    private BigDecimal taxRate;

    @Column(columnDefinition = "text")
    private String options;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean available = true;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;

    /** JSON {@code [{stockItemId, quantity}]}: what one portion draws from the outlet's store. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "recipe", columnDefinition = "text")
    private String recipeJson;

    /** The recipe as a list, which is how it is served ({@code recipe: [{stockItemId, quantity}]}). */
    @com.fasterxml.jackson.annotation.JsonProperty("recipe")
    @jakarta.persistence.Transient
    public java.util.List<com.hospio.fnb.rules.Recipes.Line> recipeLines() {
        return com.hospio.fnb.rules.Recipes.read(recipeJson);
    }

    @Column(name = "sort_order", nullable = false)
    @lombok.Builder.Default
    private int sortOrder = 0;
}

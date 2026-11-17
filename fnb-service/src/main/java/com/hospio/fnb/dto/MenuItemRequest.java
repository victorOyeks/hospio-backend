package com.hospio.fnb.dto;

import com.hospio.fnb.rules.RecipeInput;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * {@code options} is JSON text: {@code [{group, choices:[{name, priceDelta}], required, multiple}]}. {@code recipe} is
 * {@code [{stockItemId, quantity}]} per portion (a JSON array; a string holding one is accepted too); omit it to leave the recipe as it is,
 * send {@code []} to clear it.
 */
public record MenuItemRequest(@NotBlank String category, @NotBlank String name, String description, @NotNull BigDecimal price,
                              BigDecimal taxRate, String options, Integer sortOrder, Boolean active, RecipeInput recipe, String preparedAt) {
    /** Without "made at" (left as it is). */
    public MenuItemRequest(String category, String name, String description, BigDecimal price, BigDecimal taxRate, String options, Integer sortOrder,
                           Boolean active, RecipeInput recipe) {
        this(category, name, description, price, taxRate, options, sortOrder, active, recipe, null);
    }
}

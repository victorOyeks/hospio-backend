package com.hospio.fnb.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * {@code options} is JSON text: {@code [{group, choices:[{name, priceDelta}], required, multiple}]}. {@code recipe} is
 * {@code [{stockItemId, quantity}]} per portion (a JSON array; a string holding one is accepted too); omit it to leave the recipe as it is,
 * send {@code []} to clear it.
 */
public record MenuItemRequest(@NotBlank String category, @NotBlank String name, String description, @NotNull BigDecimal price,
                              BigDecimal taxRate, String options, Integer sortOrder, Boolean active, JsonNode recipe) {
}

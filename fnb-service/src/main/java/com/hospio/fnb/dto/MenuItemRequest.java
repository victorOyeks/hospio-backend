package com.hospio.fnb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record MenuItemRequest(@NotBlank String category, @NotBlank String name, String description, @NotNull BigDecimal price,
                              BigDecimal taxRate, String options, Integer sortOrder, Boolean active) {
}

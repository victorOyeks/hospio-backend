package com.hospio.property.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record SpecialPriceRequest(@NotBlank String name, @NotNull BigDecimal price, @NotNull LocalDate fromDate, @NotNull LocalDate toDate) {
}

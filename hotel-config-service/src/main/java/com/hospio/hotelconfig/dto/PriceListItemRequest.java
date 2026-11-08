package com.hospio.hotelconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record PriceListItemRequest(@NotBlank String name, @NotNull BigDecimal price, BigDecimal taxRate, String chargeCategory, Boolean active) {
}

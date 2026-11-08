package com.hospio.fnb.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record PriceChangeRequestDto(@NotNull BigDecimal newPrice, String reason) {
}

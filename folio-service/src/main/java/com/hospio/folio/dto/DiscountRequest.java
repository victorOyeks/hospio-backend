package com.hospio.folio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Managers only. Over the manager's limit the discount goes to the owner for approval. */
public record DiscountRequest(@NotNull BigDecimal amount, @NotBlank String reason) {
}

package com.hospio.folio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Post a charge by hand at the desk (extras, late check-out, damage). */
public record ChargeRequest(@NotBlank String description, @NotNull BigDecimal amount, BigDecimal taxAmount, String category) {
}

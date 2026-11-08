package com.hospio.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record RefundRequestDto(@NotNull @Positive BigDecimal amount, @NotBlank String reason) {
}

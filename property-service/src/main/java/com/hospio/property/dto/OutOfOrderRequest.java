package com.hospio.property.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

public record OutOfOrderRequest(@NotBlank String reason, LocalDate until) {
}

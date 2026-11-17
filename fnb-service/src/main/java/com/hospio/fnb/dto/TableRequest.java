package com.hospio.fnb.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** A table or seat. {@code seats} left out keeps the stored number; when sent it is a whole number from 1 (DEF-076). */
public record TableRequest(@NotBlank String number, @Min(value = 1, message = "Seats is a whole number from 1") @Max(500) Integer seats, Boolean active) {
}

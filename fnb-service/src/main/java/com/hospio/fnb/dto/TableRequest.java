package com.hospio.fnb.dto;

import jakarta.validation.constraints.NotBlank;

public record TableRequest(@NotBlank String number, Integer seats, Boolean active) {
}

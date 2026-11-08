package com.hospio.payment.dto;

import jakarta.validation.constraints.NotBlank;

public record DisputeRequest(@NotBlank String reason) {
}

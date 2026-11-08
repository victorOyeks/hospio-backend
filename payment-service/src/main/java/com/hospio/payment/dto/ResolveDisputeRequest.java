package com.hospio.payment.dto;

import jakarta.validation.constraints.NotBlank;

public record ResolveDisputeRequest(@NotBlank String resolution) {
}

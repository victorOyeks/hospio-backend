package com.hospio.fnb.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelRequest(@NotBlank String reason) {
}

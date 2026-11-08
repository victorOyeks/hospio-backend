package com.hospio.hotelconfig.dto;

import jakarta.validation.constraints.NotBlank;

public record ListItemRequest(@NotBlank String label, String code, Integer sortOrder, Boolean active) {
}

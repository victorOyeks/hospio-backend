package com.hospio.fnb.dto;

import jakarta.validation.constraints.NotBlank;

public record CategoryRequest(@NotBlank String name, Integer sortOrder, Boolean active) {
}

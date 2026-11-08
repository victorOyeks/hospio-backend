package com.hospio.property.dto;

import jakarta.validation.constraints.NotBlank;

public record AreaRequest(@NotBlank String name, String kind, Integer sortOrder) {
}

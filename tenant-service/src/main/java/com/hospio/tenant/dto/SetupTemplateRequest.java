package com.hospio.tenant.dto;

import jakarta.validation.constraints.NotBlank;

public record SetupTemplateRequest(@NotBlank String name, String description, @NotBlank String settings, Boolean active) {
}

package com.hospio.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SetPinRequest(@NotBlank @Pattern(regexp = "\\d{4,6}") String pin) {
}

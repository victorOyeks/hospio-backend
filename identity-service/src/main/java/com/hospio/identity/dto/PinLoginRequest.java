package com.hospio.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Staff tablet sign-in: phone or staff email plus a 4-6 digit PIN. */
public record PinLoginRequest(@NotBlank String identifier, @NotBlank @Pattern(regexp = "\\d{4,6}") String pin) {
}

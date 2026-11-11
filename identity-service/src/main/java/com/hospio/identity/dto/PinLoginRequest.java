package com.hospio.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Unlock a shared device: the person's id (the tile they tapped; their phone or email also work) and their 6-digit PIN.
 * The device token travels in the X-Device-Token header.
 */
public record PinLoginRequest(@NotBlank String identifier, @NotBlank @Pattern(regexp = "\\d{6}", message = "A PIN is 6 digits") String pin) {
}

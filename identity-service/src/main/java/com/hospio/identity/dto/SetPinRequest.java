package com.hospio.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Me → set or change my PIN: the new 4-6 digit PIN and the person's password, so a PIN is only set by its owner. */
public record SetPinRequest(@NotBlank @Pattern(regexp = "\\d{4,6}", message = "A PIN is 4 to 6 digits") String pin,
                            @NotBlank(message = "Enter your password") String password) {
}

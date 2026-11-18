package com.hospio.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Self-serve sign-up: the owner, their password and the business they are setting up. {@code referralCode} (optional) is the marketer's
 * code from the sign-up link (?ref=HM-...) or typed in; an unknown or inactive code is refused with a message (docs/plan-marketers.md).
 */
public record SignUpRequest(@NotBlank String fullName, @NotBlank @Email String email, @NotBlank String phone,
                            @NotBlank @Size(min = 8, max = 72) String password, @NotBlank String businessName, String city,
                            @Size(max = 20) String referralCode) {
}

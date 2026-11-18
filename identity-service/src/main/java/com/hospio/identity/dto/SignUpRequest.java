package com.hospio.identity.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Self-serve sign-up: the owner, their password and the business they are setting up. {@code referralCode} (optional) is the marketer's
 * code from the sign-up link (?ref=HM-...) or typed in; an unknown or inactive code is refused with a message (docs/plan-marketers.md).
 * {@code acceptTerms} and {@code acceptDebit} are the two required boxes (Terms, Privacy Policy and Data Processing Terms; the 1.5% daily
 * debit authority). The web app records the acceptance itself right after (platform POST /legal/accept, method SELF_SIGN_UP,
 * docs/legal-acceptance.md); sign-up only refuses an owner who did not tick them.
 */
public record SignUpRequest(@NotBlank String fullName, @NotBlank @Email String email, @NotBlank String phone,
                            @NotBlank @Size(min = 8, max = 72) String password, @NotBlank String businessName, String city,
                            @Size(max = 20) String referralCode,
                            @AssertTrue(message = "Tick the box to agree to the Terms of Service, Privacy Policy and Data Processing Terms") boolean acceptTerms,
                            @AssertTrue(message = "Tick the box to authorise the daily 1.5% debit") boolean acceptDebit) {
}

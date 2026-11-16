package com.hospio.tenant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A login created during onboarding, as the wizards save it in the draft and the handover pack lists it. The time fields are
 * kept as the ISO text the web app sent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record OnboardingLogin(String userId, String fullName, String email, String role, String hotelId, String hotelName,
                              String temporaryPassword, String temporaryPasswordExpiresAt, String expiresAt, String inviteToken,
                              String inviteExpiresAt) {

    /** The owner's login, added when the handover creates it. */
    public static OnboardingLogin owner(String userId, String fullName, String email) {
        return new OnboardingLogin(userId, fullName, email, "OWNER", null, null, null, null, null, null, null);
    }
}

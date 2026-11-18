package com.hospio.tenant.dto;

import com.hospio.tenant.entity.Business;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * identity-service sign-up: the business with the id identity chose, so both schemas agree from the start. {@code referralCode} is the
 * marketer's code from the sign-up link (?ref=) or typed by the owner; tenant resolves it to the marketer through platform-service.
 */
public record InternalCreateBusinessRequest(@NotBlank String id, @NotBlank String name, String city, String ownerUserId,
                                            @NotBlank String ownerName, @NotBlank @Email String ownerEmail, String ownerPhone,
                                            Business.Source source, String marketerId, String referralId,
                                            String referralCode) {
}

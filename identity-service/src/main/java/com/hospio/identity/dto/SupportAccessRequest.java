package com.hospio.identity.dto;

import jakarta.validation.constraints.NotBlank;

/** "Why are you looking?" - a platform admin's reason for viewing a business as its owner. */
public record SupportAccessRequest(@NotBlank String businessId, @NotBlank String reason, Integer minutes) {
}

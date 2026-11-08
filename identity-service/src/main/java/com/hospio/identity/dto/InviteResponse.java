package com.hospio.identity.dto;

import java.time.Instant;

/** What the accept-invite screen shows before the person chooses a password. */
public record InviteResponse(String fullName, String email, String role, String businessName, String hotelName, Instant expiresAt,
                             boolean accepted) {
}

package com.hospio.identity.dto;

import java.time.Instant;

/** Shown once: the temporary password (72 hours) and the invite link token (7 days) that lets the person set their own. */
public record UserCreatedResponse(String userId, String temporaryPassword, Instant temporaryPasswordExpiresAt,
                                  String inviteToken, Instant inviteExpiresAt) {
}

package com.hospio.identity.dto;

/** The temporary password is shown once and expires in 72 hours. */
public record UserCreatedResponse(String userId, String temporaryPassword, java.time.Instant temporaryPasswordExpiresAt) {
}

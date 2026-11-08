package com.hospio.identity.dto;

import java.util.List;

/** What sign-in returns. Field names match the previous API so hospio-frontend keeps working. */
public record AuthResponse(String accessToken, String refreshToken, long expiresIn, List<String> roles,
                           boolean mustChangePassword, boolean twoStepRequired) {
}

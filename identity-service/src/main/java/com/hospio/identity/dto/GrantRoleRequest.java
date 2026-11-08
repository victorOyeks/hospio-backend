package com.hospio.identity.dto;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** Give someone a role at a hotel, at a level, optionally only until a date (temporary access). */
public record GrantRoleRequest(@NotNull Role role, AccessLevel level, String hotelId, Instant expiresAt) {
}

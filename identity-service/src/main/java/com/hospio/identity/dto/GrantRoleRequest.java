package com.hospio.identity.dto;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Give someone a role at a hotel, at a level, optionally only for a while (temporary access): {@code until} is
 * the last day the role works (it ends at midnight after it, Lagos time); {@code expiresAt} is the exact
 * instant instead, for callers that already have one. Neither means the role has no end.
 */
public record GrantRoleRequest(@NotNull(message = "Choose a role") Role role, AccessLevel level, String hotelId, Instant expiresAt,
                               LocalDate until) {

    public GrantRoleRequest(Role role, AccessLevel level, String hotelId, Instant expiresAt) {
        this(role, level, hotelId, expiresAt, null);
    }
}

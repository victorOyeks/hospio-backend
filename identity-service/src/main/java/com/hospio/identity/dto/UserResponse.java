package com.hospio.identity.dto;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import java.time.Instant;
import java.util.List;

public record UserResponse(String id, String fullName, String email, String phone, String businessId, String status,
                           boolean mustChangePassword, boolean twoStepEnabled, boolean hasPin, boolean phoneVerified, String language,
                           Instant lastSignInAt, Instant createdAt, List<Grant> grants, InviteStatus inviteStatus) {

    public record Grant(String id, Role role, AccessLevel level, String hotelId, boolean paused, Instant expiresAt) {
    }

    /** The latest invite: waiting to be accepted, accepted, run out, or never issued. */
    public enum InviteStatus { PENDING, ACCEPTED, EXPIRED, NONE }
}

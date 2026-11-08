package com.hospio.identity.dto;

import java.time.Instant;
import java.util.List;

/** GET /users/me, in the shape hospio-frontend reads: roles, hotels, changedPassword. */
public record MeResponse(String id, String fullName, String email, String phoneNumber, String businessId, String userStatus,
                         List<String> roles, List<RoleLevel> roleLevels, boolean verified, boolean changedPassword,
                         boolean twoStepEnabled, List<String> hotelIds, Instant createdAt) {

    public record RoleLevel(String role, String level, String hotelId) {
    }
}

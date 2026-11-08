package com.hospio.tenant.dto;

import java.time.Instant;

public record HandoverResponse(String id, String businessId, String packKey, Instant sentAt, String sentBy, Instant acceptedAt,
                               OwnerLogin ownerLogin) {
    /** Shown once when the handover is sent. */
    public record OwnerLogin(String userId, String email, String temporaryPassword, Instant expiresAt) {
    }
}

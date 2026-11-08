package com.hospio.tenant.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/** The handover pack: the owner's login (shown once), the hotels set up, and every login created during onboarding. */
public record HandoverResponse(String id, String businessId, String packKey, Instant sentAt, String sentBy, Instant acceptedAt,
                               OwnerLogin ownerLogin, List<HotelResponse> hotels, List<JsonNode> logins) {
    /** Shown once when the handover is sent. */
    public record OwnerLogin(String userId, String email, String temporaryPassword, Instant expiresAt, String inviteToken,
                             Instant inviteExpiresAt) {
    }
}

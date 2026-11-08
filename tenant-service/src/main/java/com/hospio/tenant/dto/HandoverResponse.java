package com.hospio.tenant.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.delivery.Delivery;
import java.time.Instant;
import java.util.List;

/**
 * The handover pack: the owner's login (shown once), the hotels set up, every login created during onboarding,
 * and the deliveries that would have gone out (the owner's temporary password and invite from identity, plus
 * the HANDOVER_PACK email) when {@code hospio.deliveries.echo} is on.
 */
public record HandoverResponse(String id, String businessId, String packKey, Instant sentAt, String sentBy, Instant acceptedAt,
                               OwnerLogin ownerLogin, List<HotelResponse> hotels, List<JsonNode> logins, List<Delivery> deliveries) {
    /** Shown once when the handover is sent. */
    public record OwnerLogin(String userId, String email, String temporaryPassword, Instant expiresAt, String inviteToken,
                             Instant inviteExpiresAt) {
    }
}

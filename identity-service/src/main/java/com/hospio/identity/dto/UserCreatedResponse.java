package com.hospio.identity.dto;

import com.hospio.common.delivery.Delivery;
import java.time.Instant;
import java.util.List;

/**
 * Shown once: the temporary password (72 hours) and the invite link token (7 days) that lets the person set their own.
 * {@code deliveries} is what would have been emailed or texted (temporary password, invite link), echoed when
 * {@code hospio.deliveries.echo} is on; empty otherwise.
 */
public record UserCreatedResponse(String userId, String temporaryPassword, Instant temporaryPasswordExpiresAt,
                                  String inviteToken, Instant inviteExpiresAt, List<Delivery> deliveries) {

    public UserCreatedResponse(String userId, String temporaryPassword, Instant temporaryPasswordExpiresAt, String inviteToken,
                               Instant inviteExpiresAt) {
        this(userId, temporaryPassword, temporaryPasswordExpiresAt, inviteToken, inviteExpiresAt, List.of());
    }

    public UserCreatedResponse withDeliveries(List<Delivery> sent) {
        return new UserCreatedResponse(userId, temporaryPassword, temporaryPasswordExpiresAt, inviteToken, inviteExpiresAt,
                sent == null ? List.of() : sent);
    }
}

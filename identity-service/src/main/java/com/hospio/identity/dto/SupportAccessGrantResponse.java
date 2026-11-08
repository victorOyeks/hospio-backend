package com.hospio.identity.dto;

import java.time.Instant;

/** One support-access grant for the business's Contract & activity tab: who looked, why, and until when. */
public record SupportAccessGrantResponse(String id, String adminId, String adminName, String businessId, String reason,
                                         Instant createdAt, Instant expiresAt, boolean expired) {
}

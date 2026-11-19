package com.hospio.tenant.dto;

import java.time.Instant;

/**
 * Whether a business is suspended (docs/suspension.md). {@code reason} is the admin's note only when they chose to show it to the
 * business; {@code reasonType} is UNPAID_INVOICE or OTHER. Read by common {@code BusinessSuspension} and by the hotel app's suspended screen.
 */
public record SuspensionStatus(boolean suspended, Instant since, String reasonType, String reason) {
    public static final SuspensionStatus NONE = new SuspensionStatus(false, null, null, null);
}

package com.hospio.identity.dto;

import com.hospio.common.delivery.Delivery;
import java.time.Instant;
import java.util.List;

/**
 * The phone code waiting for the person. {@code expiresAt}: when the code stops working (null when none is valid);
 * {@code deliveries}: (echo on) the SMS that carried it; {@code sent}: whether this call sent a new code (false when one
 * was sent less than the cooldown ago, or the call only looked); {@code retryInSeconds}: seconds before "Send again"
 * may be asked (0 = now).
 */
public record PhoneCodeResponse(Instant expiresAt, List<Delivery> deliveries, boolean sent, long retryInSeconds) {

    public PhoneCodeResponse(Instant expiresAt, List<Delivery> deliveries) {
        this(expiresAt, deliveries, true, 0);
    }
}

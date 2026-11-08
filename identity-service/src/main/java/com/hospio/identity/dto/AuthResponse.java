package com.hospio.identity.dto;

import com.hospio.common.delivery.Delivery;
import java.util.List;

/**
 * What sign-in returns. Field names match the previous API so hospio-frontend keeps working. {@code deliveries}
 * is filled by sign-up (the phone code that was texted) when {@code hospio.deliveries.echo} is on; empty otherwise.
 */
public record AuthResponse(String accessToken, String refreshToken, long expiresIn, List<String> roles,
                           boolean mustChangePassword, boolean twoStepRequired, List<Delivery> deliveries) {

    public AuthResponse(String accessToken, String refreshToken, long expiresIn, List<String> roles, boolean mustChangePassword,
                        boolean twoStepRequired) {
        this(accessToken, refreshToken, expiresIn, roles, mustChangePassword, twoStepRequired, List.of());
    }

    public AuthResponse withDeliveries(List<Delivery> sent) {
        return new AuthResponse(accessToken, refreshToken, expiresIn, roles, mustChangePassword, twoStepRequired,
                sent == null ? List.of() : sent);
    }
}

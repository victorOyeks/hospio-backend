package com.hospio.identity.dto;

import com.hospio.common.delivery.Delivery;
import java.util.List;

/**
 * What sign-in returns. Field names match the previous API so hospio-frontend keeps working. {@code deliveries}
 * is filled by sign-up (the phone code that was texted) when {@code hospio.deliveries.echo} is on; empty otherwise.
 * With 2-step on, the password step answers no tokens, {@code twoStepRequired} true and {@code twoStepToken}, which
 * POST /auth/sign-in/two-step takes with the code (docs/profile.md).
 */
public record AuthResponse(String accessToken, String refreshToken, long expiresIn, List<String> roles,
                           boolean mustChangePassword, boolean twoStepRequired, List<Delivery> deliveries, String twoStepToken) {

    public AuthResponse(String accessToken, String refreshToken, long expiresIn, List<String> roles, boolean mustChangePassword,
                        boolean twoStepRequired) {
        this(accessToken, refreshToken, expiresIn, roles, mustChangePassword, twoStepRequired, List.of(), null);
    }

    /** The password was right; a code from the authenticator app (or a recovery code) is still owed. */
    public static AuthResponse codeNeeded(String twoStepToken) {
        return new AuthResponse(null, null, 0, List.of(), false, true, List.of(), twoStepToken);
    }

    public AuthResponse withDeliveries(List<Delivery> sent) {
        return new AuthResponse(accessToken, refreshToken, expiresIn, roles, mustChangePassword, twoStepRequired,
                sent == null ? List.of() : sent, twoStepToken);
    }
}

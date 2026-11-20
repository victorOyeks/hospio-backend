package com.hospio.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Moving a signed-in person between the hotel app and the admin console with a one-time code (docs/sign-in.md). */
public final class HandoffDtos {

    private HandoffDtos() {
    }

    /**
     * Ask for a code for {@code target} ({@code hotel} or {@code admin}). {@code next}: a page of the target app (a relative path).
     * {@code endSession}: this sign-in was made on the wrong app and is not kept; its session ends as the code is issued.
     */
    public record Issue(@NotBlank @Size(max = 20) String target, @Size(max = 512) String next, Boolean endSession) {
    }

    /** {@code url} is the target app's /auth/handoff address with the code; it works once, within {@code expiresIn} seconds. */
    public record Issued(String url, String target, long expiresIn) {
    }

    public record Exchange(@NotBlank @Size(max = 100) String code, @NotBlank @Size(max = 20) String target) {
    }

    /** The target app's own session, as a sign-in returns it, and the page to open. */
    public record Exchanged(String accessToken, String refreshToken, long expiresIn, List<String> roles, boolean mustChangePassword,
                            String next, boolean twoStepSetupRequired) {
    }
}

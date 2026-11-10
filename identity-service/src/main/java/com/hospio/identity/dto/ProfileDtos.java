package com.hospio.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** My profile & security (docs/profile.md): requests and answers of /users/me/** and the 2-step sign-in step. */
public final class ProfileDtos {

    private ProfileDtos() {
    }

    /** Only what is sent changes; an empty phone or language clears it. */
    public record UpdateProfile(@Size(max = 150) String fullName, @Size(max = 30) String phone, @Size(max = 10) String language) {
    }

    public record Session(String id, String kind, String device, String deviceName, String location, Instant signedInAt,
                          Instant lastUsedAt, boolean current) {
    }

    /** Sign out every other session: how many ended, and fresh tokens for this one. */
    public record SessionsEnded(int ended, AuthResponse session) {
    }

    public record TwoStepStatus(boolean enabled, Instant enabledAt, long recoveryCodesLeft, boolean required) {
    }

    public record TwoStepSetup(String secret, String otpauthUri, String qrSvg) {
    }

    public record Code(@NotBlank @Size(max = 40) String code) {
    }

    public record Disable(@NotBlank String password, @NotBlank @Size(max = 40) String code) {
    }

    public record RecoveryCodes(List<String> recoveryCodes) {
    }

    public record TwoStepSignIn(@NotBlank @Size(max = 100) String twoStepToken, @NotBlank @Size(max = 40) String code) {
    }

    public record NotificationPreferences(boolean inApp, Map<String, Boolean> email, String sms, String whatsapp) {
    }

    public record UpdatePreferences(Map<String, Boolean> email) {
    }
}

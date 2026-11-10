package com.hospio.identity.entity;

import com.hospio.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A person who can sign in. Platform admins have no business; everyone else belongs to one. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_accounts")
public class UserAccount extends BaseEntity {

    public enum Status { ACTIVE, SUSPENDED, PAUSED }

    @Column(name = "business_id", length = 36)
    private String businessId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false, unique = true)
    private String email;

    private String phone;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "pin_hash")
    private String pinHash;

    @Column(name = "failed_pin_attempts", nullable = false)
    @lombok.Builder.Default
    private int failedPinAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "failed_sign_in_attempts", nullable = false)
    @lombok.Builder.Default
    private int failedSignInAttempts = 0;

    @Column(name = "sign_in_locked_until")
    private Instant signInLockedUntil;

    @Column(name = "phone_code_attempts", nullable = false)
    @lombok.Builder.Default
    private int phoneCodeAttempts = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @lombok.Builder.Default
    private Status status = Status.ACTIVE;

    @Column(name = "must_change_password", nullable = false)
    @lombok.Builder.Default
    private boolean mustChangePassword = false;

    @Column(name = "temporary_password_expires_at")
    private Instant temporaryPasswordExpiresAt;

    @Column(name = "two_step_enabled", nullable = false)
    @lombok.Builder.Default
    private boolean twoStepEnabled = false;

    /** AES-GCM encrypted (TwoStepSecrets); the authenticator app's secret once 2-step is on. */
    @Column(name = "two_step_secret")
    private String twoStepSecret;

    /** The secret shown at setup, until the first code confirms it. */
    @Column(name = "two_step_pending_secret")
    private String twoStepPendingSecret;

    @Column(name = "two_step_enabled_at")
    private Instant twoStepEnabledAt;

    /** The last 30-second step a code was accepted for, so a code works once. */
    @Column(name = "two_step_last_step")
    private Long twoStepLastStep;

    /** SHA-256 of the token that carries a password sign-in to the code step, and when it runs out. */
    @Column(name = "two_step_challenge_hash", length = 128)
    private String twoStepChallengeHash;

    @Column(name = "two_step_challenge_expires_at")
    private Instant twoStepChallengeExpiresAt;

    @Column(name = "photo_key")
    private String photoKey;

    /** Changes with every new photo (epoch millis); null without one. */
    @Column(name = "photo_version")
    private Long photoVersion;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    /** Email categories turned off, comma separated (NotificationPreferences). */
    @Column(name = "email_off", length = 200)
    private String emailOff;

    private String language;

    @Column(name = "last_sign_in_at")
    private Instant lastSignInAt;

    @Column(name = "phone_verified", nullable = false)
    @lombok.Builder.Default
    private boolean phoneVerified = false;

    /** SHA-256 of the 6-digit code sent to the phone, until it is used or expires. */
    @Column(name = "phone_code_hash", length = 128)
    private String phoneCodeHash;

    @Column(name = "phone_code_expires_at")
    private Instant phoneCodeExpiresAt;

    /** A pause ends by itself at this instant (Lagos midnight after the last paused day); null while it lasts until resumed. */
    @Column(name = "paused_until")
    private Instant pausedUntil;

    /** Why the account was suspended or paused, kept until it is active again. */
    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "status_changed_by", length = 150)
    private String statusChangedBy;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Column(name = "job_title", length = 100)
    private String jobTitle;

    /** A pause whose end has passed: the account counts as active (the hourly job also sets it back). */
    public boolean pauseHasEnded(Instant now) {
        return status == Status.PAUSED && pausedUntil != null && !pausedUntil.isAfter(now);
    }

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    public boolean isPlatformAccount() {
        return businessId == null;
    }
}

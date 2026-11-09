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

    @Column(name = "two_step_secret")
    private String twoStepSecret;

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

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    public boolean isPlatformAccount() {
        return businessId == null;
    }
}

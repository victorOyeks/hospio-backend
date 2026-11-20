package com.hospio.identity.entity;

import com.hospio.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A one-time pass from one web app to the other (docs/sign-in.md). Only the SHA-256 of the code is stored; the code itself goes
 * to the browser once, in the redirect to the target app, which swaps it for that app's own session straight away.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "handoff_codes")
public class HandoffCode extends BaseEntity {

    @Column(name = "code_hash", nullable = false, unique = true, length = 128)
    private String codeHash;

    /** The person the code signs in; nobody else can be signed in with it. */
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /** The app it may be used on: {@code hotel} or {@code admin}. */
    @Column(name = "target", nullable = false, length = 20)
    private String target;

    /** A page of the target app to open after the exchange (a relative path, checked again by the app). */
    @Column(name = "next_path", length = 512)
    private String nextPath;

    /** The session the code was asked from; signing that session out before the exchange voids the code. Null when it ended on purpose. */
    @Column(name = "session_id", length = 36)
    private String sessionId;

    /** The session family of the browser that asked: the session opened with the code joins it, so "Sign out" ends both. */
    @Column(name = "family_id", length = 36)
    private String familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;
}

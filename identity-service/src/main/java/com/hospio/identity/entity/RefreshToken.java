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

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Set when the session was opened with a PIN on a shared device: locking or removing the device ends it. */
    @Column(name = "device_id", length = 36)
    private String deviceId;

    /** One sign-in: kept by every rotated token, carried in the access token as {@code sid}. */
    @Column(name = "session_id", length = 36)
    private String sessionId;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(name = "location", length = 100)
    private String location;

    @Column(name = "signed_in_at")
    private Instant signedInAt;

    /** Set when the session was signed out (as opposed to a token rotated by a refresh). */
    @Column(name = "ended_at")
    private Instant endedAt;

    public boolean isUsable() {
        return revokedAt == null && expiresAt.isAfter(Instant.now());
    }
}

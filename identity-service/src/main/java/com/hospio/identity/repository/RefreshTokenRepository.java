package com.hospio.identity.repository;

import com.hospio.identity.entity.RefreshToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByUserIdAndRevokedAtIsNull(String userId);

    List<RefreshToken> findByDeviceIdAndRevokedAtIsNull(String deviceId);

    /** The person's live tokens: one per session that can still refresh (docs/profile.md). */
    @Query("select t from RefreshToken t where t.userId = :userId and t.revokedAt is null and t.expiresAt > :now")
    List<RefreshToken> findLive(@Param("userId") String userId, @Param("now") Instant now);

    List<RefreshToken> findBySessionIdAndRevokedAtIsNull(String sessionId);

    /** True when the session was signed out, read from the database (not the persistence context). */
    @Query("select count(t) > 0 from RefreshToken t where t.sessionId = :sessionId and t.endedAt is not null")
    boolean sessionEnded(@Param("sessionId") String sessionId);
}

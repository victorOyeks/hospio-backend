package com.hospio.identity.repository;

import com.hospio.identity.entity.HandoffCode;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface HandoffCodeRepository extends JpaRepository<HandoffCode, String> {

    Optional<HandoffCode> findByCodeHash(String codeHash);

    /**
     * Marks the code used if nobody has yet: 1 for the one caller that wins, 0 for a replay (also when two requests race with the
     * same code). The database decides, not the persistence context.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update HandoffCode c set c.usedAt = :now, c.updatedAt = :now where c.id = :id and c.usedAt is null")
    int markUsed(@Param("id") String id, @Param("now") Instant now);

    /** Codes that ran out before {@code cutoff}, deleted by the nightly clean-up. */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM identity.handoff_codes WHERE expires_at < :cutoff", nativeQuery = true)
    int deleteExpired(@Param("cutoff") Instant cutoff);
}

package com.hospio.platform.repository;

import com.hospio.platform.entity.PlatformChange;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PlatformChangeRepository extends JpaRepository<PlatformChange, String>, JpaSpecificationExecutor<PlatformChange> {
    List<PlatformChange> findByBusinessIdOrderByCreatedAtDesc(String businessId);

    List<PlatformChange> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Retention (docs/data-retention.md): up to {@code batch} changes made before the cutoff whose undo window has ended by {@code now}.
     * A change that can still be undone is never deleted. The caller repeats until it answers 0.
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM platform.platform_changes WHERE id IN (SELECT id FROM platform.platform_changes "
            + "WHERE created_at < :cutoff AND undo_until < :now ORDER BY created_at LIMIT :batch)", nativeQuery = true)
    int deleteExpired(@Param("cutoff") Instant cutoff, @Param("now") Instant now, @Param("batch") int batch);
}

package com.hospio.messaging.repository;

import com.hospio.messaging.entity.OutboundMessage;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboundMessageRepository extends JpaRepository<OutboundMessage, String> {
    Optional<OutboundMessage> findByReference(String reference);

    /** Up to {@code limit} due messages, locked for this transaction; rows another instance holds are skipped. */
    @Query(value = "SELECT id FROM messaging.outbound_messages WHERE status = 'QUEUED' AND next_attempt_at <= :now ORDER BY next_attempt_at "
            + "LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<String> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    /** Claims them until {@code until}: nobody else sends them meanwhile; a worker that died lets them go again then. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OutboundMessage m set m.nextAttemptAt = :until where m.id in :ids")
    int lease(@Param("ids") Collection<String> ids, @Param("until") Instant until);
}

package com.hospio.platform.repository;

import com.hospio.platform.entity.ActivityEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityEntryRepository extends JpaRepository<ActivityEntry, String> {
    Optional<ActivityEntry> findByEventId(String eventId);

    Page<ActivityEntry> findByHotelIdOrderByOccurredAtDesc(String hotelId, Pageable pageable);

    Page<ActivityEntry> findByBusinessIdOrderByOccurredAtDesc(String businessId, Pageable pageable);

    Page<ActivityEntry> findByBusinessIdAndActorNameStartingWithOrderByOccurredAtDesc(String businessId, String actorPrefix, Pageable pageable);

    List<ActivityEntry> findByActorNameStartingWithOrderByOccurredAtDesc(String actorPrefix, Pageable pageable);

    long countByBusinessIdAndOccurredAtAfter(String businessId, Instant since);

    long countByBusinessIdAndEventTypeAndOccurredAtBetween(String businessId, String eventType, Instant from, Instant to);

    long countByBusinessIdAndEventTypeAndOccurredAtAfter(String businessId, String eventType, Instant since);

    long countByHotelIdAndEventTypeAndOccurredAtAfter(String hotelId, String eventType, Instant since);

    boolean existsByBusinessIdAndEventType(String businessId, String eventType);

    @Query("select count(distinct a.actorId) from ActivityEntry a where a.businessId = :businessId and a.occurredAt >= :since "
            + "and a.actorId is not null and a.actorId <> 'system' and (a.actorName is null or a.actorName not like 'H.M.S%') "
            + "and (a.actorName is null or a.actorName not like 'system%')")
    long countDistinctActorsSince(@Param("businessId") String businessId, @Param("since") Instant since);
}

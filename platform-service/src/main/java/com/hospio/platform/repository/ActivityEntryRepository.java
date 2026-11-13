package com.hospio.platform.repository;

import com.hospio.platform.entity.ActivityEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;

public interface ActivityEntryRepository extends JpaRepository<ActivityEntry, String>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<ActivityEntry> {
    Optional<ActivityEntry> findByEventId(String eventId);

    /**
     * One log line, written once per event id however often the event arrives (redeliveries, several consumers): the unique event_id
     * decides, no read first. Answers 1 when written, 0 when the event was already logged.
     */
    @Modifying
    @Query(value = "INSERT INTO platform.activity_log (id, business_id, hotel_id, event_id, event_type, topic, actor_id, actor_name, summary, payload, "
            + "occurred_at, created_at, updated_at) VALUES (:id, :businessId, :hotelId, :eventId, :eventType, :topic, :actorId, :actorName, :summary, "
            + ":payload, :occurredAt, :now, :now) ON CONFLICT (event_id) DO NOTHING", nativeQuery = true)
    int insertOnce(@Param("id") String id, @Param("businessId") String businessId, @Param("hotelId") String hotelId, @Param("eventId") String eventId,
                   @Param("eventType") String eventType, @Param("topic") String topic, @Param("actorId") String actorId,
                   @Param("actorName") String actorName, @Param("summary") String summary, @Param("payload") String payload,
                   @Param("occurredAt") Instant occurredAt, @Param("now") Instant now);

    /** Retention: removes up to {@code batch} entries older than the cutoff; the caller repeats until it answers 0. */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM platform.activity_log WHERE id IN (SELECT id FROM platform.activity_log WHERE occurred_at < :cutoff LIMIT :batch)",
            nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff, @Param("batch") int batch);

    Page<ActivityEntry> findByHotelIdOrderByOccurredAtDesc(String hotelId, Pageable pageable);

    Page<ActivityEntry> findByBusinessIdOrderByOccurredAtDesc(String businessId, Pageable pageable);

    Page<ActivityEntry> findByBusinessIdAndActorNameStartingWithOrderByOccurredAtDesc(String businessId, String actorPrefix, Pageable pageable);

    List<ActivityEntry> findByActorNameStartingWithOrderByOccurredAtDesc(String actorPrefix, Pageable pageable);

    /** Recent entries by an actor-name prefix: the time bound lets the occurred_at index serve it instead of reading the whole log. */
    List<ActivityEntry> findByActorNameStartingWithAndOccurredAtAfterOrderByOccurredAtDesc(String actorPrefix, Instant since, Pageable pageable);

    long countByBusinessIdAndOccurredAtAfter(String businessId, Instant since);

    boolean existsByBusinessIdAndOccurredAtAfter(String businessId, Instant since);

    boolean existsByBusinessIdAndEventTypeAndOccurredAtAfter(String businessId, String eventType, Instant since);

    /**
     * Usage tab, weekly: per week since {@code from} (0 = the first) and event type, how many. Weeks are 7-day steps from {@code from},
     * so the bucketing uses the same timestamps the other counts compare against.
     */
    @Query(value = "SELECT CAST(FLOOR(EXTRACT(EPOCH FROM (occurred_at - CAST(:from AS TIMESTAMP))) / 604800) AS INTEGER) AS week, event_type, COUNT(*) "
            + "FROM platform.activity_log WHERE business_id = :businessId AND event_type IN (:types) AND occurred_at >= :from AND occurred_at < :to "
            + "GROUP BY 1, 2", nativeQuery = true)
    List<Object[]> weeklyCounts(@Param("businessId") String businessId, @Param("types") java.util.Collection<String> types,
                                @Param("from") Instant from, @Param("to") Instant to);

    /** Per event type, how many since a time (one query for several counts). */
    @Query("select a.eventType, count(a) from ActivityEntry a where a.businessId = :businessId and a.eventType in :types and a.occurredAt >= :since "
            + "group by a.eventType")
    List<Object[]> countsByType(@Param("businessId") String businessId, @Param("types") java.util.Collection<String> types, @Param("since") Instant since);

    long countByBusinessIdAndEventTypeAndOccurredAtBetween(String businessId, String eventType, Instant from, Instant to);

    long countByBusinessIdAndEventTypeAndOccurredAtAfter(String businessId, String eventType, Instant since);

    long countByHotelIdAndEventTypeAndOccurredAtAfter(String hotelId, String eventType, Instant since);

    boolean existsByBusinessIdAndEventType(String businessId, String eventType);

    @Query("select count(distinct a.actorId) from ActivityEntry a where a.businessId = :businessId and a.occurredAt >= :since "
            + "and a.actorId is not null and a.actorId <> 'system' and (a.actorName is null or (a.actorName not like 'Hospio360%' and a.actorName not like 'H.M.S%')) "
            + "and (a.actorName is null or a.actorName not like 'system%')")
    long countDistinctActorsSince(@Param("businessId") String businessId, @Param("since") Instant since);
}

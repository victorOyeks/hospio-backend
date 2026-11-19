package com.hospio.platform.repository;

import com.hospio.platform.entity.ActivityEntry;
import java.time.Instant;
import java.time.LocalDate;
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

    /**
     * Retention (docs/data-retention.md): up to {@code batch} of the oldest entries before the cutoff go, in one statement. Before they go,
     * each one is counted into activity_daily_counts (business, Lagos day, event type: no person, no text) and its person's last time is
     * kept in activity_people_seen (people only, as {@link #countDistinctActorsSince} counts them), so usage, health and alerts can still
     * look further back than the log. Rows without a business are not counted. The caller repeats until it answers 0.
     */
    @Modifying
    @Transactional
    @Query(value = "WITH old AS (SELECT id, business_id, event_type, occurred_at, actor_id, actor_name FROM platform.activity_log "
            + "WHERE occurred_at < :cutoff ORDER BY occurred_at LIMIT :batch FOR UPDATE SKIP LOCKED), "
            + "counted AS (INSERT INTO platform.activity_daily_counts (business_id, day, event_type, events) "
            + "SELECT business_id, CAST((occurred_at AT TIME ZONE 'UTC') AT TIME ZONE 'Africa/Lagos' AS DATE), event_type, COUNT(*) FROM old "
            + "WHERE business_id IS NOT NULL GROUP BY 1, 2, 3 "
            + "ON CONFLICT (business_id, event_type, day) DO UPDATE SET events = platform.activity_daily_counts.events + EXCLUDED.events), "
            + "seen AS (INSERT INTO platform.activity_people_seen (business_id, actor_id, last_seen_at) "
            + "SELECT business_id, actor_id, MAX(occurred_at) FROM old WHERE business_id IS NOT NULL AND " + PEOPLE + " GROUP BY 1, 2 "
            + "ON CONFLICT (business_id, actor_id) DO UPDATE SET last_seen_at = GREATEST(platform.activity_people_seen.last_seen_at, EXCLUDED.last_seen_at)) "
            + "DELETE FROM platform.activity_log WHERE id IN (SELECT id FROM old)", nativeQuery = true)
    int rollUpAndDeleteOlderThan(@Param("cutoff") Instant cutoff, @Param("batch") int batch);

    /** The people the distinct-people counts count: a signed-in person, not the system, Hospio360 staff or H.M.S. */
    String PEOPLE = "actor_id IS NOT NULL AND actor_id <> 'system' AND (actor_name IS NULL OR (actor_name NOT LIKE 'Hospio360%' "
            + "AND actor_name NOT LIKE 'H.M.S%' AND actor_name NOT LIKE 'system%'))";

    /** Retention of the summaries themselves: a person's "last seen" is only read for 30 days, counts for a year (feature adoption). */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM platform.activity_people_seen WHERE last_seen_at < :before", nativeQuery = true)
    int forgetPeopleSeenBefore(@Param("before") Instant before);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM platform.activity_daily_counts WHERE day < :before", nativeQuery = true)
    int forgetCountsBefore(@Param("before") LocalDate before);

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
     * so the bucketing uses the same timestamps the other counts compare against. Days already past the activity log's retention come
     * from activity_daily_counts ({@code fromDay}/{@code toDay} are the Lagos days of {@code from}/{@code to}).
     */
    @Query(value = "SELECT week, event_type, SUM(n) FROM ("
            + "SELECT CAST(FLOOR(EXTRACT(EPOCH FROM (occurred_at - CAST(:from AS TIMESTAMP))) / 604800) AS INTEGER) AS week, event_type, COUNT(*) AS n "
            + "FROM platform.activity_log WHERE business_id = :businessId AND event_type IN (:types) AND occurred_at >= :from AND occurred_at < :to "
            + "GROUP BY 1, 2 UNION ALL "
            + "SELECT CAST(FLOOR((day - CAST(:fromDay AS DATE)) / 7.0) AS INTEGER), event_type, SUM(events) FROM platform.activity_daily_counts "
            + "WHERE business_id = :businessId AND event_type IN (:types) AND day >= :fromDay AND day < :toDay GROUP BY 1, 2"
            + ") t GROUP BY 1, 2", nativeQuery = true)
    List<Object[]> weeklyCounts(@Param("businessId") String businessId, @Param("types") java.util.Collection<String> types,
                                @Param("from") Instant from, @Param("to") Instant to, @Param("fromDay") LocalDate fromDay, @Param("toDay") LocalDate toDay);

    /** Per event type, how many since a time (one query for several counts); days past retention from activity_daily_counts. */
    @Query(value = "SELECT event_type, SUM(n) FROM ("
            + "SELECT event_type, COUNT(*) AS n FROM platform.activity_log WHERE business_id = :businessId AND event_type IN (:types) "
            + "AND occurred_at >= :since GROUP BY 1 UNION ALL "
            + "SELECT event_type, SUM(events) FROM platform.activity_daily_counts WHERE business_id = :businessId AND event_type IN (:types) "
            + "AND day >= :sinceDay GROUP BY 1) t GROUP BY 1", nativeQuery = true)
    List<Object[]> countsByType(@Param("businessId") String businessId, @Param("types") java.util.Collection<String> types,
                                @Param("since") Instant since, @Param("sinceDay") LocalDate sinceDay);

    /** Did the business do this since then: the log, or the daily counts for days past retention. */
    @Query(value = "SELECT (EXISTS (SELECT 1 FROM platform.activity_log WHERE business_id = :businessId AND event_type = :type AND occurred_at >= :since) "
            + "OR EXISTS (SELECT 1 FROM platform.activity_daily_counts WHERE business_id = :businessId AND event_type = :type AND day >= :sinceDay))",
            nativeQuery = true)
    boolean happenedSince(@Param("businessId") String businessId, @Param("type") String type, @Param("since") Instant since,
                          @Param("sinceDay") LocalDate sinceDay);

    /** Has the business ever done this: the log or the daily counts. */
    @Query(value = "SELECT (EXISTS (SELECT 1 FROM platform.activity_log WHERE business_id = :businessId AND event_type = :type) "
            + "OR EXISTS (SELECT 1 FROM platform.activity_daily_counts WHERE business_id = :businessId AND event_type = :type))", nativeQuery = true)
    boolean everHappened(@Param("businessId") String businessId, @Param("type") String type);

    long countByBusinessIdAndEventTypeAndOccurredAtBetween(String businessId, String eventType, Instant from, Instant to);

    long countByBusinessIdAndEventTypeAndOccurredAtAfter(String businessId, String eventType, Instant since);

    long countByHotelIdAndEventTypeAndOccurredAtAfter(String hotelId, String eventType, Instant since);

    /** Different people who did something since then: the log, plus those whose entries are past retention (activity_people_seen). */
    @Query(value = "SELECT COUNT(*) FROM (SELECT actor_id FROM platform.activity_log WHERE business_id = :businessId AND occurred_at >= :since AND "
            + PEOPLE + " UNION SELECT actor_id FROM platform.activity_people_seen WHERE business_id = :businessId AND last_seen_at >= :since) t",
            nativeQuery = true)
    long countDistinctActorsSince(@Param("businessId") String businessId, @Param("since") Instant since);
}

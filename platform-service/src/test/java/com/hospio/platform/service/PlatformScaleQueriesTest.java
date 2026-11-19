package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.entity.FeeStatement;
import com.hospio.platform.entity.HealthScore;
import com.hospio.platform.entity.Report;
import com.hospio.platform.repository.ActivityEntryRepository;
import com.hospio.platform.repository.FeeStatementRepository;
import com.hospio.platform.repository.HealthScoreRepository;
import com.hospio.platform.repository.ReportRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The scale fixes' SQL in a real Postgres: the activity log written once per event id with no read first, the retention delete in
 * batches, the weekly usage counts in one grouped query, fee sums and the guarded OVERDUE update, health buckets counted in the database
 * and a report schedule claimed by one instance only. Needs Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.admin.fail-fast=false",
        "services.internal.api-key=test-key",
        "hospio.worker.enabled=false",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-test"
})
class PlatformScaleQueriesTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("platform-schema.sql");

    @Autowired
    ActivityEntryRepository activity;
    @Autowired
    FeeStatementRepository statements;
    @Autowired
    HealthScoreRepository health;
    @Autowired
    ReportRepository reports;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txm;

    @BeforeEach
    void clean() {
        TenantContext.set(Tenant.system("test"));
        jdbc.update("delete from platform.activity_log");
        jdbc.update("delete from platform.activity_daily_counts");
        jdbc.update("delete from platform.activity_people_seen");
        jdbc.update("delete from platform.platform_changes");
        jdbc.update("delete from platform.fee_statements");
        jdbc.update("delete from platform.health_scores");
        jdbc.update("delete from platform.reports");
    }

    @AfterEach
    void out() {
        TenantContext.clear();
    }

    private int log(String eventId, String business, String type, Instant at) {
        return new TransactionTemplate(txm).execute(st -> activity.insertOnce(UUID.randomUUID().toString(), business, "h1", eventId, type, "hospio-x",
                "u1", "Ada", type, "{}", at, Instant.now()));
    }

    @Test
    void anEventIsLoggedOnceHoweverOftenItArrives() {
        String id = UUID.randomUUID().toString();
        assertThat(log(id, "b1", "BookingCreated", Instant.now())).isEqualTo(1);
        assertThat(log(id, "b1", "BookingCreated", Instant.now())).isZero();
        // Events without an id are not deduplicated (as before).
        assertThat(log(null, "b1", "BookingCreated", Instant.now())).isEqualTo(1);
        assertThat(log(null, "b1", "BookingCreated", Instant.now())).isEqualTo(1);
        assertThat(activity.count()).isEqualTo(3);
    }

    @Test
    void retentionDeletesOldEntriesInBatchesUntilNoneAreLeftAndKeepsTheirCounts() {
        Instant old = Instant.parse("2026-01-10T12:00:00Z");
        for (int i = 0; i < 7; i++) {
            log(UUID.randomUUID().toString(), "b1", "BookingCreated", old);
        }
        log(UUID.randomUUID().toString(), "b1", "DayClosed", old);
        log(UUID.randomUUID().toString(), "b1", "BookingCreated", Instant.now());
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        assertThat(activity.rollUpAndDeleteOlderThan(cutoff, 3)).isEqualTo(3);
        assertThat(activity.rollUpAndDeleteOlderThan(cutoff, 3)).isEqualTo(3);
        assertThat(activity.rollUpAndDeleteOlderThan(cutoff, 3)).isEqualTo(2);
        assertThat(activity.rollUpAndDeleteOlderThan(cutoff, 3)).isZero();
        // The newer entry stays; the old ones are counts now (no text, no person), added up across batches.
        assertThat(activity.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select events from platform.activity_daily_counts where business_id = 'b1' and event_type = 'BookingCreated' "
                + "and day = date '2026-01-10'", Long.class)).isEqualTo(7);
        assertThat(activity.everHappened("b1", "DayClosed")).isTrue();
        assertThat(activity.everHappened("b1", "RoomCleaned")).isFalse();
        assertThat(activity.happenedSince("b1", "DayClosed", old.minus(1, ChronoUnit.DAYS), java.time.LocalDate.parse("2026-01-09"))).isTrue();
        assertThat(activity.happenedSince("b1", "DayClosed", old.plus(1, ChronoUnit.DAYS), java.time.LocalDate.parse("2026-01-11"))).isFalse();
        // Ada (u1) is still counted as active since before the cutoff, once.
        assertThat(activity.countDistinctActorsSince("b1", old.minus(1, ChronoUnit.DAYS))).isEqualTo(1);
    }

    @Test
    void usageWeeksAddTheLogAndTheDailyCounts() {
        Instant from = Instant.parse("2026-09-07T00:00:00Z").minus(1, ChronoUnit.HOURS); // Monday 7 Sep, 00:00 Lagos
        Instant to = from.plus(56, ChronoUnit.DAYS);
        log(UUID.randomUUID().toString(), "b1", "BookingCreated", from.plus(1, ChronoUnit.HOURS));
        log(UUID.randomUUID().toString(), "b1", "BookingCreated", from.plus(8, ChronoUnit.DAYS));
        activity.rollUpAndDeleteOlderThan(from.plus(2, ChronoUnit.DAYS), 100); // the first one becomes a count
        long[][] weeks = OverviewService.weekly(activity.weeklyCounts("b1", OverviewService.WEEKLY_TYPES, from, to,
                java.time.LocalDate.parse("2026-09-07"), java.time.LocalDate.parse("2026-11-02")), 8);
        assertThat(weeks[0]).containsExactly(1, 0, 0);
        assertThat(weeks[1]).containsExactly(1, 0, 0);
        assertThat(activity.countsByType("b1", OverviewService.WEEKLY_TYPES, from, java.time.LocalDate.parse("2026-09-07")))
                .singleElement().satisfies(r -> assertThat(((Number) r[1]).longValue()).isEqualTo(2));
    }

    @Test
    void aChangeIsDeletedOnlyOnceItsUndoWindowHasEnded() {
        Instant now = Instant.now();
        change("old", now.minus(10, ChronoUnit.DAYS), now.minus(3, ChronoUnit.DAYS));
        change("six-days", now.minus(6, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS));
        // Older than the cutoff but still undoable (a window longer than 7 days): kept.
        change("still-undoable", now.minus(9, ChronoUnit.DAYS), now.plus(1, ChronoUnit.HOURS));
        assertThat(changes.deleteExpired(now.minus(7, ChronoUnit.DAYS), now, 10)).isEqualTo(1);
        assertThat(jdbc.queryForList("select id from platform.platform_changes order by id", String.class))
                .containsExactly("six-days", "still-undoable");
    }

    @Autowired
    com.hospio.platform.repository.PlatformChangeRepository changes;

    private void change(String id, Instant at, Instant undoUntil) {
        jdbc.update("insert into platform.platform_changes (id, business_id, area, description, reason, undo_until, created_at, updated_at) "
                + "values (?, 'b1', 'Rates', 'x', 'y', ?, ?, ?)", id, java.sql.Timestamp.from(undoUntil), java.sql.Timestamp.from(at), java.sql.Timestamp.from(at));
    }

    @Test
    void usageCountsPerWeekAndTypeInOneQuery() {
        Instant from = Instant.parse("2026-09-07T00:00:00Z");
        Instant to = from.plus(56, ChronoUnit.DAYS);
        log(UUID.randomUUID().toString(), "b1", "BookingCreated", from);
        log(UUID.randomUUID().toString(), "b1", "BookingCreated", from.plus(6, ChronoUnit.DAYS).plus(23, ChronoUnit.HOURS));
        log(UUID.randomUUID().toString(), "b1", "PaymentApproved", from.plus(7, ChronoUnit.DAYS));
        log(UUID.randomUUID().toString(), "b1", "MessageSent", from.plus(55, ChronoUnit.DAYS));
        log(UUID.randomUUID().toString(), "b1", "MessageSent", to);
        log(UUID.randomUUID().toString(), "b2", "BookingCreated", from);
        log(UUID.randomUUID().toString(), "b1", "RoomCleaned", from);

        long[][] weeks = OverviewService.weekly(activity.weeklyCounts("b1", OverviewService.WEEKLY_TYPES, from, to,
                java.time.LocalDate.parse("2026-09-07"), java.time.LocalDate.parse("2026-11-02")), 8);
        assertThat(weeks[0]).containsExactly(2, 0, 0);
        assertThat(weeks[1]).containsExactly(0, 1, 0);
        assertThat(weeks[7]).containsExactly(0, 0, 1);
        assertThat(activity.countsByType("b1", OverviewService.WEEKLY_TYPES, from, java.time.LocalDate.parse("2026-09-07"))).hasSize(3);
        assertThat(activity.existsByBusinessIdAndEventTypeAndOccurredAtAfter("b1", "RoomCleaned", from.minus(1, ChronoUnit.DAYS))).isTrue();
        assertThat(activity.existsByBusinessIdAndEventTypeAndOccurredAtAfter("b1", "DayClosed", from.minus(1, ChronoUnit.DAYS))).isFalse();
        assertThat(activity.existsByBusinessIdAndOccurredAtAfter("b2", to)).isFalse();
    }

    private FeeStatement statement(String business, String month, String fee, String paid, String status, LocalDate dueOn) {
        return statements.saveAndFlush(FeeStatement.builder().businessId(business).month(month).baseAmount(new BigDecimal("1000"))
                .feeAmount(new BigDecimal(fee)).paidAmount(new BigDecimal(paid)).status(status).dueOn(dueOn).build());
    }

    @Test
    void feeSumsAndTheGuardedOverdueRule() {
        LocalDate today = LocalDate.parse("2026-11-13");
        FeeStatement late = statement("b1", "2026-09", "500.00", "100.00", "PART_PAID", LocalDate.parse("2026-10-01"));
        FeeStatement inGrace = statement("b2", "2026-10", "300.00", "0.00", "DUE", LocalDate.parse("2026-11-10"));
        FeeStatement settled = statement("b3", "2026-10", "200.00", "200.00", "PART_PAID", LocalDate.parse("2026-10-01"));
        statement("b4", "2026-10", "50.00", "0.00", "OVERDUE", LocalDate.parse("2026-09-01"));

        assertThat(statements.sumOutstanding(List.of("DUE", "PART_PAID"))).isEqualByComparingTo("700.00");
        assertThat(statements.sumBilled("2026-10")).isEqualByComparingTo("550.00");
        assertThat(statements.sumBilled("2020-01")).isEqualByComparingTo("0");

        int flipped = new TransactionTemplate(txm).execute(st -> {
            statements.markSettled(Instant.now());
            return statements.markOverdue(FeeService.overdueCutoff(today, 5), Instant.now());
        });
        assertThat(flipped).isEqualTo(1);
        assertThat(statements.findById(late.getId()).orElseThrow().getStatus()).isEqualTo("OVERDUE");
        assertThat(statements.findById(inGrace.getId()).orElseThrow().getStatus()).isEqualTo("DUE");
        assertThat(statements.findById(settled.getId()).orElseThrow().getStatus()).isEqualTo("PAID");
        assertThat(statements.countByBusinessIdAndStatus("b4", "OVERDUE")).isEqualTo(1);
    }

    @Test
    void healthScoresAreBucketedInTheDatabase() {
        for (int score : new int[]{90, 70, 69, 40, 39, 0}) {
            health.saveAndFlush(HealthScore.builder().businessId(UUID.randomUUID().toString()).score(score).build());
        }
        Object[] row = health.buckets(HealthService.GOOD, HealthService.WATCH).get(0);
        assertThat(OverviewService.asLong(row[0])).isEqualTo(2);
        assertThat(OverviewService.asLong(row[1])).isEqualTo(2);
        assertThat(OverviewService.asLong(row[2])).isEqualTo(2);
    }

    @Test
    void aDueScheduleIsClaimedByOneInstanceOnly() {
        Instant due = Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        Report r = reports.saveAndFlush(Report.builder().businessId("b1").kind("DAILY_SUMMARY").name("Daily summary").status("SCHEDULED")
                .schedule("DAILY").nextRunAt(due).build());
        Instant read = reports.findById(r.getId()).orElseThrow().getNextRunAt();
        Instant next = Instant.now().plus(1, ChronoUnit.DAYS);
        assertThat(reports.claim(r.getId(), read, next, Instant.now())).isEqualTo(1);
        assertThat(reports.claim(r.getId(), read, next, Instant.now())).isZero();
        assertThat(reports.findByStatusAndNextRunAtLessThanEqual("SCHEDULED", Instant.now())).isEmpty();
    }
}

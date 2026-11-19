package com.hospio.platform.jobs;

import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.repository.ActivityEntryRepository;
import com.hospio.platform.repository.PlatformChangeRepository;
import com.hospio.platform.service.PlatformSettingsService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Audit logs are kept 7 days, then deleted (owner decision, 19 Nov 2026; docs/data-retention.md). Every night at 03:30 Lagos:
 * <ul>
 *   <li>the activity log (the hotel app's Activity log, the admin's business Activity tab and Admin activity): entries older than the
 *   retention go, 5,000 at a time, each batch one short statement. Each entry is first counted into activity_daily_counts and its person
 *   into activity_people_seen (no text, no detail), which is what usage, health scores and alerts read past the 7 days;</li>
 *   <li>Changes by Hospio360 (platform_changes): changes older than the retention whose undo window has also ended. A change still
 *   undoable is never deleted, whatever the setting.</li>
 * </ul>
 * Retention is the admin setting Security → audit retention days; {@code hospio.retention.audit-days} (AUDIT_RETENTION_DAYS, 7) only when
 * that isn't set or readable. Never less than {@link #MIN_DAYS}. Business records (bookings, payments, orders, folios, invoices, fees,
 * stock, legal acceptances, deliveries, notifications) are not touched. Runs as the platform; off with WORKER_ENABLED=false.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hospio.worker.enabled", havingValue = "true", matchIfMissing = true)
public class AuditRetentionJob {

    static final int DEFAULT_DAYS = 7;
    /** Health scores and alerts read the last 7 days from the log itself, and a change is undoable for 7 days. */
    static final int MIN_DAYS = 7;
    static final int BATCH = 5000;
    /** A safety stop: 10,000 batches is 50 million rows in one night; what is left goes the next night. */
    static final int MAX_BATCHES = 10_000;
    /** "Active people" is read for 30 days at most; daily counts for a year (feature adoption looks back 366 days). */
    static final int PEOPLE_SEEN_DAYS = 31;
    static final int COUNTS_DAYS = 400;
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    public record Result(int days, long activityDeleted, long changesDeleted) {
    }

    private final ActivityEntryRepository entries;
    private final PlatformChangeRepository changes;
    private final PlatformSettingsService settings;
    private final int fallbackDays;

    public AuditRetentionJob(ActivityEntryRepository entries, PlatformChangeRepository changes, PlatformSettingsService settings,
                             @Value("${hospio.retention.audit-days:7}") int fallbackDays) {
        this.entries = entries;
        this.changes = changes;
        this.settings = settings;
        this.fallbackDays = fallbackDays;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Africa/Lagos")
    public void nightly() {
        try {
            run();
        } catch (RuntimeException e) {
            log.error("Audit log retention failed: {}", e.getMessage(), e);
        }
    }

    /** The retention in force, in days (what the pages say). */
    public int days() {
        return TenantContext.runWith(Tenant.system("audit-retention"),
                () -> retentionDays(settings.security().auditRetentionDays(), fallbackDays));
    }

    /** Deletes what is past retention; answers how many entries and changes went. */
    public Result run() {
        return TenantContext.runWith(Tenant.system("audit-retention"), () -> {
            int days = retentionDays(settings.security().auditRetentionDays(), fallbackDays);
            Instant now = Instant.now();
            Instant cutoff = now.minus(days, ChronoUnit.DAYS);
            long activity = 0;
            for (int i = 0; i < MAX_BATCHES; i++) {
                int n = entries.rollUpAndDeleteOlderThan(cutoff, BATCH);
                activity += n;
                if (n == 0) {
                    break;
                }
            }
            long changed = 0;
            for (int i = 0; i < MAX_BATCHES; i++) {
                int n = changes.deleteExpired(cutoff, now, BATCH);
                changed += n;
                if (n == 0) {
                    break;
                }
            }
            entries.forgetPeopleSeenBefore(now.minus(PEOPLE_SEEN_DAYS, ChronoUnit.DAYS));
            entries.forgetCountsBefore(LocalDate.now(LAGOS).minusDays(COUNTS_DAYS));
            log.info("Audit log retention ({} days): {} activity log entries and {} Changes by Hospio360 deleted", days, activity, changed);
            return new Result(days, activity, changed);
        });
    }

    /** The setting as whole days: a number or numeric text, at least {@link #MIN_DAYS}; anything unreadable is the fallback. */
    public static int retentionDays(Object value, int fallback) {
        int days;
        if (value instanceof Number n) {
            days = n.intValue();
        } else if (value != null) {
            try {
                days = (int) Double.parseDouble(value.toString().trim());
            } catch (NumberFormatException e) {
                days = fallback;
            }
        } else {
            days = fallback;
        }
        return Math.max(days, MIN_DAYS);
    }

    public static int retentionDays(Object value) {
        return retentionDays(value, DEFAULT_DAYS);
    }
}

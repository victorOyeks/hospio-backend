package com.hospio.platform.jobs;

import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.repository.ActivityEntryRepository;
import com.hospio.platform.service.PlatformSettingsService;
import com.hospio.platform.settings.PlatformSettingsCatalogue;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every night at 02:15 Lagos: activity-log entries older than the security setting {@code auditRetentionDays} (default 365) are
 * deleted, 5,000 at a time, each batch its own short transaction so the log stays writable while it runs. Never keeps less than
 * {@link #MIN_DAYS} days whatever the setting says. Runs as the platform; off with WORKER_ENABLED=false, like the nightly job.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hospio.worker.enabled", havingValue = "true", matchIfMissing = true)
public class ActivityRetentionJob {

    static final int DEFAULT_DAYS = 365;
    static final int MIN_DAYS = 30;
    static final int BATCH = 5000;
    /** A safety stop: 10,000 batches is 50 million rows in one night; what is left goes the next night. */
    static final int MAX_BATCHES = 10_000;

    private final ActivityEntryRepository entries;
    private final PlatformSettingsService settings;

    @Scheduled(cron = "0 15 2 * * *", zone = "Africa/Lagos")
    public void nightly() {
        try {
            run();
        } catch (RuntimeException e) {
            log.error("Activity log retention failed: {}", e.getMessage(), e);
        }
    }

    /** Deletes what is past retention; answers how many entries went. */
    public long run() {
        return TenantContext.runWith(Tenant.system("activity-retention"), () -> {
            int days = retentionDays(settings.group(PlatformSettingsCatalogue.SECURITY).get("auditRetentionDays"));
            Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
            long deleted = 0;
            for (int i = 0; i < MAX_BATCHES; i++) {
                int n = entries.deleteOlderThan(cutoff, BATCH);
                deleted += n;
                if (n == 0) {
                    break;
                }
            }
            if (deleted > 0) {
                log.info("Activity log retention: {} entries older than {} days deleted", deleted, days);
            }
            return deleted;
        });
    }

    /** The setting as whole days: a number or numeric text, at least {@link #MIN_DAYS}; anything unreadable is the default. */
    public static int retentionDays(Object value) {
        int days;
        if (value instanceof Number n) {
            days = n.intValue();
        } else if (value != null) {
            try {
                days = (int) Double.parseDouble(value.toString().trim());
            } catch (NumberFormatException e) {
                days = DEFAULT_DAYS;
            }
        } else {
            days = DEFAULT_DAYS;
        }
        return Math.max(days, MIN_DAYS);
    }
}

package com.hospio.identity.service;

import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.identity.repository.RefreshTokenRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every refresh and every sign-in writes a refresh token row and nothing removed them. Nightly at 03:20 (Lagos) the ones
 * that expired or were revoked more than 30 days ago are deleted, 10,000 per transaction so the table is never locked
 * for long. Live sessions are untouched (their newest token is neither expired nor revoked).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {

    static final Duration KEEP = Duration.ofDays(30);
    static final int BATCH = 10_000;
    /** At most this many batches a night (10 million rows); the rest waits for tomorrow. */
    static final int MAX_BATCHES = 1_000;

    private final RefreshTokenRepository refreshTokens;

    @Scheduled(cron = "0 20 3 * * *", zone = "Africa/Lagos")
    public void run() {
        int deleted = purge(Instant.now().minus(KEEP));
        if (deleted > 0) {
            log.info("Deleted {} expired or revoked refresh token(s)", deleted);
        }
    }

    /** Deletes tokens expired or revoked before {@code cutoff} in batches; returns how many. Refresh tokens are platform-only rows. */
    int purge(Instant cutoff) {
        return TenantContext.runWith(Tenant.system("refresh-token-cleanup"), () -> {
            int total = 0;
            for (int i = 0; i < MAX_BATCHES; i++) {
                int n = refreshTokens.deleteStale(cutoff, BATCH);
                total += n;
                if (n < BATCH) {
                    break;
                }
            }
            return total;
        });
    }
}

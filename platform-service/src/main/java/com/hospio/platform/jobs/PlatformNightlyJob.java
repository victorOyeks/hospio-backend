package com.hospio.platform.jobs;

import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.service.AlertService;
import com.hospio.platform.service.FeeService;
import com.hospio.platform.service.HealthService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every night at 01:30 Lagos: statements past their grace period become OVERDUE, every LIVE business gets a
 * fresh health score, and the alert rules are swept (raised where a cause appeared, dismissed where it ended).
 * Runs as the platform. Off with WORKER_ENABLED=false.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hospio.worker.enabled", havingValue = "true", matchIfMissing = true)
public class PlatformNightlyJob {

    private final FeeService fees;
    private final HealthService health;
    private final AlertService alerts;
    private final TenantClient tenant;

    @Scheduled(cron = "0 30 1 * * *", zone = "Africa/Lagos")
    public void nightly() {
        run();
    }

    public void run() {
        TenantContext.runWith(Tenant.system("platform-nightly"), () -> {
            int overdue = safe("fees overdue", () -> fees.markOverdue());
            List<TenantClient.Business> live = tenant.businesses("LIVE", null);
            int scored = safe("health", () -> health.computeAll(live));
            safe("alerts", () -> {
                alerts.sweep(live, tenant.onboarding(null));
                return 0;
            });
            log.info("Nightly platform job: {} statements overdue, {} businesses scored, alerts swept", overdue, scored);
        });
    }

    private static int safe(String step, java.util.function.IntSupplier work) {
        try {
            return work.getAsInt();
        } catch (RuntimeException e) {
            log.error("Nightly platform job step '{}' failed: {}", step, e.getMessage(), e);
            return 0;
        }
    }
}

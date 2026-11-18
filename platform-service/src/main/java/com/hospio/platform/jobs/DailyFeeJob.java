package com.hospio.platform.jobs;

import com.hospio.platform.service.DailyFeeService;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hospio's daily fee (docs/plan-daily-fees.md). 00:05 Lagos: yesterday is worked out for every hotel billed daily; the two days
 * before are worked out too if a run missed them. Nothing is emailed and nothing is debited: the days show in the app and are billed on the
 * monthly invoice (MonthlyInvoiceJob, docs/plan-monthly-invoices.md). One instance runs it (SingleInstanceScheduling). Off with
 * WORKER_ENABLED=false.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hospio.worker.enabled", havingValue = "true", matchIfMissing = true)
public class DailyFeeJob {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final DailyFeeService fees;

    @Scheduled(cron = "0 5 0 * * *", zone = "Africa/Lagos")
    public void workOut() {
        LocalDate today = LocalDate.now(LAGOS);
        for (int back = 3; back >= 1; back--) {
            try {
                DailyFeeService.RunResult r = fees.runDay(today.minusDays(back));
                if (r.worked() > 0 || !r.notes().isEmpty()) {
                    log.info("Daily fee {}: {} worked out, {} skipped; {}", r.date(), r.worked(), r.skipped(), r.notes());
                }
            } catch (RuntimeException e) {
                log.error("Daily fee for {} failed: {}", today.minusDays(back), e.getMessage(), e);
            }
        }
    }
}

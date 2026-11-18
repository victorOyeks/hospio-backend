package com.hospio.platform.jobs;

import com.hospio.platform.service.CommissionService;
import java.time.YearMonth;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 07:00 Lagos on the 5th: every marketer's commission statement for the month before (docs/plan-marketers.md). Off with WORKER_ENABLED=false. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hospio.worker.enabled", havingValue = "true", matchIfMissing = true)
public class MarketerStatementJob {

    private final CommissionService commission;

    @Scheduled(cron = "0 0 7 5 * *", zone = "Africa/Lagos")
    public void statements() {
        YearMonth month = YearMonth.now(ZoneId.of("Africa/Lagos")).minusMonths(1);
        try {
            CommissionService.StatementRun r = commission.makeStatements(month);
            log.info("Marketer statements for {}: {} made, {} carried over, {} skipped {}", r.month(), r.made(), r.carriedOver(), r.skipped(), r.notes());
        } catch (RuntimeException e) {
            log.error("Marketer statements for {} failed: {}", month, e.getMessage(), e);
        }
    }
}

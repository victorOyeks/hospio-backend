package com.hospio.platform.jobs;

import com.hospio.platform.service.MonthlyInvoiceService;
import java.time.LocalDate;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The monthly invoice (docs/plan-monthly-invoices.md), Lagos time: 07:00 on the 1st, last month's invoices (after messaging-service's
 * guest-message invoices at 00:30); 06:00 daily, the restriction of invoices past their last day; 08:00 daily, the reminders. One instance
 * runs each (SingleInstanceScheduling). Off with WORKER_ENABLED=false.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hospio.worker.enabled", havingValue = "true", matchIfMissing = true)
public class MonthlyInvoiceJob {

    private final MonthlyInvoiceService invoices;

    @Scheduled(cron = "0 0 7 1 * *", zone = "Africa/Lagos")
    public void issue() {
        LocalDate today = LocalDate.now(MonthlyInvoiceService.LAGOS);
        try {
            invoices.issueAll(YearMonth.from(today).minusMonths(1), today);
        } catch (RuntimeException e) {
            log.error("Monthly invoice run failed: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Africa/Lagos")
    public void restrict() {
        try {
            MonthlyInvoiceService.StepRun r = invoices.restrictDue(LocalDate.now(MonthlyInvoiceService.LAGOS), null);
            if (r.restricted() > 0 || !r.notes().isEmpty()) {
                log.info("Monthly invoices {}: {} overdue, {} restricted {}", r.date(), r.overdue(), r.restricted(), r.notes());
            }
        } catch (RuntimeException e) {
            log.error("Monthly invoice restriction step failed: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "0 0 8 * * *", zone = "Africa/Lagos")
    public void remind() {
        try {
            MonthlyInvoiceService.StepRun r = invoices.remind(LocalDate.now(MonthlyInvoiceService.LAGOS), null);
            if (r.notices() > 0 || !r.notes().isEmpty()) {
                log.info("Monthly invoices {}: {} notices {}", r.date(), r.notices(), r.notes());
            }
        } catch (RuntimeException e) {
            log.error("Monthly invoice reminders failed: {}", e.getMessage(), e);
        }
    }
}

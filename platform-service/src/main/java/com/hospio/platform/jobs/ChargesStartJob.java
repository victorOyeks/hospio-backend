package com.hospio.platform.jobs;

import com.hospio.platform.service.ChargesStartService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * "Charges start on" (docs/plan-monthly-invoices.md): 08:15 Lagos daily, a bell and email to every Super admin and Finance admin for each
 * business whose charges start in 7 days (once per business and date). One instance runs it (SingleInstanceScheduling). Off with
 * WORKER_ENABLED=false.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hospio.worker.enabled", havingValue = "true", matchIfMissing = true)
public class ChargesStartJob {

    private final ChargesStartService starts;

    @Scheduled(cron = "0 15 8 * * *", zone = "Africa/Lagos")
    public void remind() {
        try {
            starts.remind(LocalDate.now(ChargesStartService.LAGOS));
        } catch (RuntimeException e) {
            log.error("Charges start reminders failed: {}", e.getMessage(), e);
        }
    }
}

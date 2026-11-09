package com.hospio.payment.jobs;

import com.hospio.payment.service.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every night at 02:00 Lagos: yesterday's settlements. Off with WORKER_ENABLED=false. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hospio.worker.enabled", havingValue = "true", matchIfMissing = true)
public class SettlementJob {

    private final SettlementService settlements;

    @Scheduled(cron = "0 0 2 * * *", zone = "Africa/Lagos")
    public void nightly() {
        try {
            settlements.runNightly();
        } catch (RuntimeException e) {
            log.error("Nightly settlement run failed: {}", e.getMessage(), e);
        }
    }
}

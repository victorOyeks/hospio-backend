package com.hospio.platform.jobs;

import com.hospio.platform.service.DeviceInvoiceService;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Monthly device invoices (docs/plan-device-packages.md), 07:00 and 15:00 Lagos: issue what is due, debit what is open (once a day;
 * a pending debit is asked about), then move the overdue along. One instance runs it (SingleInstanceScheduling).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hospio.worker.enabled", havingValue = "true", matchIfMissing = true)
public class DeviceInvoiceJob {

    private final DeviceInvoiceService invoices;

    @Scheduled(cron = "0 0 7,15 * * *", zone = "Africa/Lagos")
    public void run() {
        try {
            invoices.run(LocalDate.now(ZoneId.of("Africa/Lagos")));
        } catch (RuntimeException e) {
            log.error("Device invoice run failed: {}", e.getMessage(), e);
        }
    }
}

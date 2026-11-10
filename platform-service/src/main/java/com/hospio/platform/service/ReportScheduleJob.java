package com.hospio.platform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs report schedules that are due, every five minutes (docs/owner-portal.md · Reports). */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportScheduleJob {

    private final ReportScheduleService schedules;

    @Scheduled(fixedDelayString = "${hospio.reports.schedule-check-ms:300000}", initialDelay = 60000)
    public void run() {
        int ran = schedules.runDue();
        if (ran > 0) {
            log.info("Sent {} scheduled report(s)", ran);
        }
    }
}

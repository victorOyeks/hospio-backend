package com.hospio.identity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ends timed pauses (Owner portal · Staff, "pause for 7 days"). Every hour at ten past (Lagos), so a pause whose last
 * day was yesterday is over by 00:10; sign-in also lets the person in as soon as the pause has run out.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountPauseJob {

    private final StaffAccountService staff;

    @Scheduled(cron = "0 10 * * * *", zone = "Africa/Lagos")
    public void run() {
        int resumed = staff.resumeDuePauses();
        if (resumed > 0) {
            log.info("Ended {} timed pause(s)", resumed);
        }
    }
}

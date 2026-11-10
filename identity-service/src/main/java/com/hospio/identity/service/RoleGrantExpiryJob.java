package com.hospio.identity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ends temporary roles. Every hour at five past (Lagos), so a role whose last day was yesterday is gone by
 * 00:05 and a restart over midnight catches up within the hour. Tokens stop carrying the role at midnight
 * anyway (only active grants go into a token); this takes it off the lists, logs it and tells the person.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoleGrantExpiryJob {

    private final UserService users;

    @Scheduled(cron = "0 5 * * * *", zone = "Africa/Lagos")
    public void run() {
        int ended = users.endExpiredGrants();
        if (ended > 0) {
            log.info("Ended {} temporary role(s)", ended);
        }
    }
}

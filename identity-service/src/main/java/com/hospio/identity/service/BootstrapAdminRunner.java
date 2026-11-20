package com.hospio.identity.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs the bootstrap super admin check (BootstrapAdminService) once the service is up, on its own thread: sending with retries
 * can take minutes when Kafka is down, and start-up must never wait for it.
 */
@Component
@RequiredArgsConstructor
public class BootstrapAdminRunner {

    private final BootstrapAdminService bootstrap;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!bootstrap.enabled()) {
            return;
        }
        Thread.ofVirtual().name("bootstrap-admin").start(bootstrap::runOnStartup);
    }
}

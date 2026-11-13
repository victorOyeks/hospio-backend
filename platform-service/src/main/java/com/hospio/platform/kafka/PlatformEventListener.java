package com.hospio.platform.kafka;

import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalAnsweredEvent;
import com.hospio.common.kafka.events.ApprovalRequestedEvent;
import com.hospio.common.kafka.events.HospioEvent;
import com.hospio.common.tenancy.TenantBinder;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.service.ActivityLogService;
import com.hospio.platform.service.ApprovalService;
import com.hospio.platform.service.DepartmentsLiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Every action → activity log (card 12). This listener subscribes to every topic; each event is
 * logged under its business and hotel and bumps the live Departments board. ApprovalRequested also
 * opens an approval, and {@link PlatformDomainEvents} applies the admin portal's rules (marketers, alerts).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformEventListener {

    private final ActivityLogService activityLog;
    private final ApprovalService approvals;
    private final DepartmentsLiveService live;
    private final PlatformDomainEvents domain;
    private final TenantBinder tenant;

    /**
     * Three consumers share the topics' partitions (one consumer for every topic was the bottleneck). The live board's Redis bump runs
     * after the commit, outside the transaction, so a slow Redis never holds the database connection or rolls the log line back.
     */
    @Transactional
    @KafkaListener(topics = "#{T(com.hospio.common.kafka.KafkaTopics).ALL_ARRAY}", concurrency = "${hospio.platform.events.concurrency:3}")
    public void onAnyEvent(@Payload HospioEvent event, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        tenant.bind(event);
        try {
            activityLog.record(topic, event);
            if (KafkaTopics.APPROVAL_REQUESTED.equals(topic) && event instanceof ApprovalRequestedEvent e) {
                approvals.onRequested(e);
            }
            if (KafkaTopics.APPROVAL_ANSWERED.equals(topic) && event instanceof ApprovalAnsweredEvent e) {
                approvals.onAnswered(e);
            }
            try {
                domain.on(topic, event);
            } catch (Exception e) {
                log.warn("Admin portal rules not applied for {} {}: {}", event.type(), event.getEventId(), e.getMessage());
            }
            afterCommit(() -> {
                try {
                    live.bump(topic, event);
                } catch (Exception e) {
                    log.warn("Live board not updated for {}: {}", topic, e.getMessage());
                }
            });
        } finally {
            TenantContext.clear();
        }
    }

    /** Runs the work once the surrounding transaction has committed (straight away when there is none). */
    static void afterCommit(Runnable work) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    work.run();
                }
            });
        } else {
            work.run();
        }
    }
}

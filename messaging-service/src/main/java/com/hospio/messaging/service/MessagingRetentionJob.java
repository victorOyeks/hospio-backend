package com.hospio.messaging.service;

import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.messaging.repository.ConversationRepository;
import com.hospio.messaging.repository.NotificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.IntUnaryOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retention (docs/retention.md): every night at 02:45 Lagos, notifications older than {@code hospio.retention.notifications-days}
 * (env RETENTION_NOTIFICATIONS_DAYS, default 30) and chats with no message for {@code hospio.retention.chats-days}
 * (RETENTION_CHATS_DAYS, default 30) are deleted: staff Inbox threads and guest conversations, with their messages. 5,000 at a
 * time, each batch its own short transaction. Never less than 7 days whatever the setting. Delivery records (what was sent,
 * what a hotel is billed for) are not touched. One instance runs it at a time (SingleInstanceScheduling).
 */
@Slf4j
@Component
public class MessagingRetentionJob {

    static final int MIN_DAYS = 7;
    static final int BATCH = 5000;
    /** A safety stop: 2,000 batches is 10 million rows in one night; what is left goes the next night. */
    static final int MAX_BATCHES = 2000;

    private final NotificationRepository notifications;
    private final ConversationRepository conversations;
    private final int notificationDays;
    private final int chatDays;
    /** Now, a test seam. */
    Clock clock = Clock.systemUTC();

    public MessagingRetentionJob(NotificationRepository notifications, ConversationRepository conversations,
                                 @Value("${hospio.retention.notifications-days:30}") int notificationDays,
                                 @Value("${hospio.retention.chats-days:30}") int chatDays) {
        this.notifications = notifications;
        this.conversations = conversations;
        this.notificationDays = Math.max(MIN_DAYS, notificationDays);
        this.chatDays = Math.max(MIN_DAYS, chatDays);
    }

    @Scheduled(cron = "0 45 2 * * *", zone = "Africa/Lagos")
    public void nightly() {
        try {
            run();
        } catch (RuntimeException e) {
            log.error("Messaging retention failed: {}", e.getMessage(), e);
        }
    }

    /** Deletes what is past retention; answers {notifications, conversations} deleted. */
    public int[] run() {
        return TenantContext.runWith(Tenant.system("messaging-retention"), () -> {
            Instant now = Instant.now(clock);
            Instant notificationCutoff = now.minus(Duration.ofDays(notificationDays));
            Instant chatCutoff = now.minus(Duration.ofDays(chatDays));
            int n = drain(b -> notifications.deleteCreatedBefore(notificationCutoff, b));
            int c = drain(b -> conversations.deleteQuietSince(chatCutoff, b));
            if (n + c > 0) {
                log.info("Messaging retention: {} notifications older than {} days and {} chats quiet for {} days deleted",
                        n, notificationDays, c, chatDays);
            }
            return new int[] {n, c};
        });
    }

    private static int drain(IntUnaryOperator batch) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int n = batch.applyAsInt(BATCH);
            total += n;
            if (n < BATCH) {
                break;
            }
        }
        return total;
    }
}

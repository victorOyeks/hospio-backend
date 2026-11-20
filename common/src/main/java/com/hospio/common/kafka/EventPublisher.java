package com.hospio.common.kafka;

import com.hospio.common.kafka.events.HospioEvent;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes an event after the surrounding transaction commits (or immediately when there is none),
 * stamping it with the current tenant: who did it, in which business and hotel, and when. Every write
 * in Hospio goes through here, which is what the activity log depends on.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(String topic, HospioEvent event) {
        stamp(event);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(topic, event);
                }
            });
        } else {
            send(topic, event);
        }
    }

    /**
     * Sends now (no after-commit hook) and waits up to {@code timeout} for the broker's acknowledgement, for a caller that must
     * know whether the event got out (a bounded retry loop, e.g. the bootstrap super admin's email). Throws
     * {@link IllegalStateException} when it did not; the message names the event type and the cause, never the payload.
     */
    public void publishAndWait(String topic, HospioEvent event, java.time.Duration timeout) {
        stamp(event);
        String key = event.getHotelId() != null ? event.getHotelId() : event.getBusinessId();
        try {
            kafkaTemplate.send(topic, key, event).get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(event.type() + " not published: interrupted");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException e) {
            // No cause attached: a producer exception can carry the record, and the record can carry a temporary password.
            throw new IllegalStateException(event.type() + " not published: " + reason(e));
        }
    }

    private static String reason(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : " (" + root.getMessage() + ")");
    }

    private void send(String topic, HospioEvent event) {
        String key = event.getHotelId() != null ? event.getHotelId() : event.getBusinessId();
        try {
            kafkaTemplate.send(topic, key, event).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish {} {} to {}", event.type(), event.getEventId(), topic, ex);
                }
            });
        } catch (RuntimeException e) {
            // The broker could not be reached within max.block.ms (5s). The write has already committed, so the caller
            // must not see a failure for it; the event is lost and logged with its id (a transactional outbox would keep it).
            log.error("Failed to publish {} {} to {}: {}", event.type(), event.getEventId(), topic, e.getMessage());
        }
    }

    private static void stamp(HospioEvent event) {
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID().toString());
        }
        if (event.getOccurredAt() == null) {
            event.setOccurredAt(Instant.now());
        }
        Tenant t = TenantContext.current().orElse(null);
        if (t != null) {
            if (event.getBusinessId() == null) {
                event.setBusinessId(t.businessId());
            }
            if (event.getHotelId() == null) {
                event.setHotelId(t.hotelId());
            }
            if (event.getActorId() == null) {
                event.setActorId(t.actorId());
            }
            if (event.getActorName() == null) {
                event.setActorName(t.actorName());
            }
        }
    }
}

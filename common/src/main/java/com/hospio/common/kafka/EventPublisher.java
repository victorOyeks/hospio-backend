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

    private void send(String topic, HospioEvent event) {
        String key = event.getHotelId() != null ? event.getHotelId() : event.getBusinessId();
        kafkaTemplate.send(topic, key, event).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish {} to {}", event.type(), topic, ex);
            }
        });
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

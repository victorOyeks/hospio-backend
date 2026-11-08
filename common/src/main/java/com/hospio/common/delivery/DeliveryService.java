package com.hospio.common.delivery;

import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.DeliveryRequestedEvent;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes every delivery as a {@link DeliveryRequestedEvent} and, when {@code hospio.deliveries.echo} is on
 * (env {@code DELIVERIES_ECHO}, true on staging), returns the same list so the controller can put it in the
 * response under {@code deliveries}. The web apps show those on screen with a note that email and SMS are
 * not connected. Production runs with echo off and returns an empty list.
 */
@Slf4j
@Component
public class DeliveryService {

    private final ObjectProvider<EventPublisher> publisher;
    private final boolean echo;

    public DeliveryService(ObjectProvider<EventPublisher> publisher, @Value("${hospio.deliveries.echo:false}") boolean echo) {
        this.publisher = publisher;
        this.echo = echo;
    }

    public boolean echoes() {
        return echo;
    }

    /** Publishes the deliveries; returns them when echo is on, otherwise an empty list. Never throws. */
    public List<Delivery> send(List<Delivery> deliveries) {
        if (deliveries == null || deliveries.isEmpty()) {
            return List.of();
        }
        EventPublisher events = publisher.getIfAvailable();
        for (Delivery d : deliveries) {
            try {
                if (events != null) {
                    events.publish(KafkaTopics.DELIVERY_REQUESTED, DeliveryRequestedEvent.builder()
                            .channel(d.channel().name()).to(d.to()).subject(d.subject()).text(d.text())
                            .kind(d.kind().name()).ref(d.ref()).build());
                }
                log.info("Delivery {} to {} via {}{}", d.kind(), d.to(), d.channel(), echo ? " (echoed in the response)" : "");
            } catch (RuntimeException e) {
                log.warn("Delivery {} to {} not published: {}", d.kind(), d.to(), e.getMessage());
            }
        }
        return echo ? List.copyOf(deliveries) : List.of();
    }

    public List<Delivery> send(Delivery delivery) {
        return send(List.of(delivery));
    }
}

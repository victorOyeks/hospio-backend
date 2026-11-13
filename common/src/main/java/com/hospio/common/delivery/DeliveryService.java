package com.hospio.common.delivery;

import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.DeliveryRequestedEvent;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one way a service sends an email, SMS or WhatsApp (docs/email.md). Publishes every delivery as a
 * {@link DeliveryRequestedEvent} on {@code hospio-delivery-requested} after the surrounding transaction commits;
 * messaging-service records it and sends the emails through SMTP. When {@code hospio.deliveries.echo} is on (env
 * {@code DELIVERIES_ECHO}, true on staging) the same list comes back, each with its {@code status} (email sent or not
 * connected), so the controller can put it in the response under {@code deliveries} and the web app shows
 * it on screen. Production runs with echo off and gets an empty list. Never throws: a delivery never fails the action.
 */
@Slf4j
@Component
public class DeliveryService {

    private final ObjectProvider<EventPublisher> publisher;
    private final MailPolicy policy;
    private final boolean echo;

    public DeliveryService(ObjectProvider<EventPublisher> publisher, MailPolicy policy, @Value("${hospio.deliveries.echo:false}") boolean echo) {
        this.publisher = publisher;
        this.policy = policy;
        this.echo = echo;
    }

    public boolean echoes() {
        return echo;
    }

    public MailPolicy policy() {
        return policy;
    }

    /** The delivery with the status the echo shows (email sent, not connected). */
    public Delivery marked(Delivery d) {
        return d.withStatus(policy.statusFor(d));
    }

    /** Publishes the deliveries; returns them (marked) when echo is on, otherwise an empty list. Never throws. */
    public List<Delivery> send(List<Delivery> deliveries) {
        if (deliveries == null || deliveries.isEmpty()) {
            return List.of();
        }
        EventPublisher events = publisher.getIfAvailable();
        List<Delivery> out = new ArrayList<>();
        for (Delivery raw : deliveries) {
            if (raw == null) {
                continue;
            }
            // An SMS is one page; the echo shows exactly what the phone gets (docs/sms-whatsapp.md).
            Delivery d = marked(raw.channel() == Delivery.Channel.SMS
                    ? new Delivery(raw.channel(), raw.to(), raw.subject(), SmsText.fit(raw.text()), raw.kind(), raw.ref(), raw.status(),
                    raw.hotelId(), raw.businessId(), raw.template(), raw.params())
                    : raw);
            out.add(d);
            try {
                if (events != null) {
                    events.publish(KafkaTopics.DELIVERY_REQUESTED, DeliveryRequestedEvent.builder()
                            .businessId(d.businessId()).hotelId(d.hotelId())
                            .channel(d.channel().name()).to(d.to()).subject(d.subject()).text(d.text())
                            .kind(d.kind().name()).ref(d.ref()).template(d.template()).params(d.params()).build());
                }
                log.info("Delivery {} to {} via {} ({}){}", d.kind(), d.to(), d.channel(), d.status(), echo ? " echoed in the response" : "");
            } catch (RuntimeException e) {
                log.warn("Delivery {} to {} not published: {}", d.kind(), d.to(), e.getMessage());
            }
        }
        return echo ? List.copyOf(out) : List.of();
    }

    public List<Delivery> send(Delivery delivery) {
        return send(delivery == null ? List.of() : List.of(delivery));
    }
}

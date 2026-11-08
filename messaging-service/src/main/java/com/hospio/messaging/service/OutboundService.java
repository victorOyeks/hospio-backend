package com.hospio.messaging.service;

import com.hospio.common.enums.NotificationChannel;
import com.hospio.common.integrations.MessageChannel;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.MessageSentEvent;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.messaging.entity.OutboundMessage;
import com.hospio.messaging.entity.Template;
import com.hospio.messaging.repository.OutboundMessageRepository;
import com.hospio.messaging.repository.TemplateRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queue and send WhatsApp, SMS and email through the channel adapters. Sending is done by the
 * worker, never inline, so a slow provider never slows a check-out; failures retry with back-off.
 */
@Slf4j
@Service
public class OutboundService {

    private static final int MAX_ATTEMPTS = 6;

    private final OutboundMessageRepository outbound;
    private final TemplateRepository templates;
    private final Map<NotificationChannel, MessageChannel> channels;
    private final EventPublisher events;

    public OutboundService(OutboundMessageRepository outbound, TemplateRepository templates, List<MessageChannel> channels, EventPublisher events) {
        this.outbound = outbound;
        this.templates = templates;
        this.channels = channels.stream().collect(Collectors.toMap(MessageChannel::kind, Function.identity(), (a, b) -> a));
        this.events = events;
    }

    /** Queue a message; {@code reference} makes it idempotent (one receipt per payment, one summary per stay). */
    @Transactional
    public OutboundMessage queue(NotificationChannel channel, String recipient, String subject, String body, String kind, String reference) {
        if (recipient == null || recipient.isBlank()) {
            return null;
        }
        if (reference != null) {
            Optional<OutboundMessage> existing = outbound.findByReference(reference);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        return outbound.save(OutboundMessage.builder().channel(channel).recipient(recipient).subject(subject).body(body).kind(kind).reference(reference).build());
    }

    /** Fill a hotel's template (from Hotel setup) or fall back to the given default text. */
    @Transactional(readOnly = true)
    public String render(String hotelId, String key, NotificationChannel channel, String fallback, Map<String, String> values) {
        String text = templates.findByHotelIdAndKeyAndChannelAndActiveTrue(hotelId, key, channel).map(Template::getBody).orElse(fallback);
        for (Map.Entry<String, String> e : values.entrySet()) {
            text = text.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return text;
    }

    /** The worker: every 20 seconds, send what is due. */
    @Scheduled(fixedDelayString = "${hospio.messaging.send-interval-ms:20000}")
    @Transactional
    public void sendDue() {
        TenantContext.runWith(Tenant.system("outbound"), () -> {
            for (OutboundMessage m : outbound.findTop50ByStatusAndNextAttemptAtBeforeOrderByNextAttemptAt(OutboundMessage.Status.QUEUED, Instant.now())) {
                TenantContext.set(Tenant.systemFor(m.getBusinessId(), m.getHotelId(), null, "system:outbound"));
                MessageChannel channel = channels.get(m.getChannel());
                m.setAttempts(m.getAttempts() + 1);
                try {
                    if (channel == null) {
                        throw new IllegalStateException("No adapter for " + m.getChannel());
                    }
                    m.setProviderRef(channel.send(m.getRecipient(), m.getSubject(), m.getBody()));
                    m.setStatus(OutboundMessage.Status.SENT);
                    m.setSentAt(Instant.now());
                    events.publish(KafkaTopics.MESSAGE_SENT, MessageSentEvent.builder().messageId(m.getId()).channel(m.getChannel().name())
                            .to(m.getRecipient()).subject(m.getSubject() == null ? m.getKind() : m.getSubject()).build());
                } catch (Exception e) {
                    m.setLastError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    if (m.getAttempts() >= MAX_ATTEMPTS) {
                        m.setStatus(OutboundMessage.Status.FAILED);
                        log.error("Giving up on {} to {} after {} attempts: {}", m.getChannel(), m.getRecipient(), m.getAttempts(), m.getLastError());
                    } else {
                        m.setNextAttemptAt(Instant.now().plus((long) Math.pow(2, m.getAttempts()), ChronoUnit.MINUTES));
                    }
                }
                outbound.save(m);
            }
        });
    }
}

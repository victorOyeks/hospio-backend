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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Queue and send WhatsApp, SMS and email through the channel adapters. Sending is done by the
 * worker, never inline, so a slow provider never slows a check-out; failures retry with back-off.
 */
@Slf4j
@Service
public class OutboundService {

    private static final int MAX_ATTEMPTS = 6;
    private static final int BATCH = 50;
    private static final java.time.Duration LEASE = java.time.Duration.ofMinutes(10);

    private final OutboundMessageRepository outbound;
    private final TemplateRepository templates;
    private final Map<NotificationChannel, MessageChannel> channels;
    private final EventPublisher events;
    private final com.hospio.messaging.mail.MailDispatcher mail;
    private final TransactionTemplate tx;

    public OutboundService(OutboundMessageRepository outbound, TemplateRepository templates, List<MessageChannel> channels, EventPublisher events,
                           com.hospio.messaging.mail.MailDispatcher mail, TransactionTemplate tx) {
        this.mail = mail;
        this.tx = tx;
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

    /**
     * The worker: every 20 seconds, send what is due. A short transaction claims a batch (FOR UPDATE SKIP LOCKED, then a
     * lease of a few minutes), so two instances never send the same message; each is sent outside any transaction and its
     * row updated after. A worker that dies mid-batch lets the lease run out and the rows are picked up again.
     */
    @Scheduled(fixedDelayString = "${hospio.messaging.send-interval-ms:20000}")
    public void sendDue() {
        TenantContext.runWith(Tenant.system("outbound"), () -> {
            List<String> ids = tx.execute(s -> {
                Instant now = Instant.now();
                List<String> due = outbound.lockDue(now, BATCH);
                if (due != null && !due.isEmpty()) {
                    outbound.lease(due, now.plus(LEASE));
                }
                return due;
            });
            for (String id : ids == null ? List.<String>of() : ids) {
                try {
                    sendOne(id);
                } catch (RuntimeException e) {
                    log.error("Outbound message {} not processed: {}", id, e.getMessage());
                }
            }
        });
    }

    private void sendOne(String id) {
        OutboundMessage m = outbound.findById(id).filter(x -> x.getStatus() == OutboundMessage.Status.QUEUED).orElse(null);
        if (m == null) {
            return;
        }
        TenantContext.set(Tenant.systemFor(m.getBusinessId(), m.getHotelId(), null, "system:outbound"));
        String providerRef = null;
        String error = null;
        try {
            if (m.getChannel() == NotificationChannel.EMAIL || m.getChannel() == NotificationChannel.SMS || m.getChannel() == NotificationChannel.WHATSAPP) {
                // One sender for email, SMS and WhatsApp (docs/email.md, docs/sms-whatsapp.md): it records the delivery (its own
                // queue sends it). A WhatsApp here has no template, so it goes as an SMS of the same text. The message id is
                // the delivery's event id, so a second run never records it twice.
                var record = mail.accept(com.hospio.common.kafka.events.DeliveryRequestedEvent.builder().eventId(m.getId()).occurredAt(m.getCreatedAt())
                        .businessId(m.getBusinessId()).hotelId(m.getHotelId()).channel(m.getChannel().name()).to(m.getRecipient())
                        .subject(m.getSubject()).text(m.getBody()).kind(m.getKind()).ref(m.getReference()).build());
                providerRef = record == null ? null : "delivery:" + record.getId();
            } else {
                MessageChannel channel = channels.get(m.getChannel());
                if (channel == null) {
                    throw new IllegalStateException("No adapter for " + m.getChannel());
                }
                providerRef = channel.send(m.getRecipient(), m.getSubject(), m.getBody());
            }
        } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        String ref = providerRef;
        String err = error;
        tx.execute(s -> {
            OutboundMessage row = outbound.findById(id).orElse(null);
            if (row == null || row.getStatus() != OutboundMessage.Status.QUEUED) {
                return null;
            }
            row.setAttempts(row.getAttempts() + 1);
            if (err == null) {
                row.setProviderRef(ref);
                row.setStatus(OutboundMessage.Status.SENT);
                row.setSentAt(Instant.now());
                events.publish(KafkaTopics.MESSAGE_SENT, MessageSentEvent.builder().messageId(row.getId()).channel(row.getChannel().name())
                        .to(row.getRecipient()).subject(row.getSubject() == null ? row.getKind() : row.getSubject()).build());
            } else {
                row.setLastError(err.length() > 500 ? err.substring(0, 500) : err);
                if (row.getAttempts() >= MAX_ATTEMPTS) {
                    row.setStatus(OutboundMessage.Status.FAILED);
                    log.error("Giving up on {} to {} after {} attempts: {}", row.getChannel(), row.getRecipient(), row.getAttempts(), err);
                } else {
                    row.setNextAttemptAt(Instant.now().plus((long) Math.pow(2, row.getAttempts()), ChronoUnit.MINUTES));
                }
            }
            outbound.save(row);
            return null;
        });
    }
}

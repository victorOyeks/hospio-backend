package com.hospio.messaging.mail;

import com.hospio.common.kafka.events.DeliveryRequestedEvent;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.messaging.client.TenantClient;
import com.hospio.messaging.entity.DeliveryRecord;
import com.hospio.messaging.entity.Message;
import com.hospio.messaging.repository.DeliveryRecordRepository;
import com.hospio.messaging.repository.MessageRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one sender (docs/email.md). Every delivery is recorded; emails are laid out in the shared template and handed to the
 * SMTP relay on a worker thread after the record commits, with one retry on a transient failure. The answer (Message-ID
 * and reply line, or the error) goes on the record and, for a guest message, on the message. Nothing here throws back to
 * whoever asked: the action that wanted the email has already succeeded. SMS and WhatsApp go the same way through
 * {@link com.hospio.messaging.text.TextSender} (Infobip, docs/sms-whatsapp.md) when switched on, else are recorded as NOT_CONNECTED.
 */
@Slf4j
@Service
public class MailDispatcher {

    private final DeliveryRecordRepository records;
    private final MessageRepository messages;
    private final TenantClient tenants;
    private final EmailTemplates templates;
    private final SmtpGateway smtp;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final String fromAddress;
    private final String defaultName;
    private final long retryDelayMs;
    private final long maxEventAgeMinutes;
    private com.hospio.messaging.text.TextSender texts;
    private Executor executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "mail-sender");
        t.setDaemon(true);
        return t;
    });

    public MailDispatcher(DeliveryRecordRepository records, MessageRepository messages, TenantClient tenants, EmailTemplates templates,
                          SmtpGateway smtp, TransactionTemplate tx,
                          @Value("${hospio.mail.enabled:false}") boolean enabled,
                          @Value("${hospio.mail.from-address:no-reply@hospio360.com}") String fromAddress,
                          @Value("${hospio.mail.from-name-default:Hospio360}") String defaultName,
                          @Value("${hospio.mail.retry-delay-ms:5000}") long retryDelayMs,
                          @Value("${hospio.mail.max-event-age-minutes:30}") long maxEventAgeMinutes) {
        this.maxEventAgeMinutes = maxEventAgeMinutes;
        this.records = records;
        this.messages = messages;
        this.tenants = tenants;
        this.templates = templates;
        this.smtp = smtp;
        this.tx = tx;
        this.enabled = enabled;
        this.fromAddress = fromAddress;
        this.defaultName = defaultName;
        this.retryDelayMs = retryDelayMs;
    }

    /** SMS and WhatsApp (absent in the email-only tests: they are then recorded as NOT_CONNECTED). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTexts(com.hospio.messaging.text.TextSender texts) {
        this.texts = texts;
    }

    /** Tests run the send on the calling thread. */
    void runInline() {
        this.executor = Runnable::run;
    }

    /** Who an email is from: the display name and the Reply-To address (null when there is none). */
    public record From(String name, String replyTo) {
    }

    /** The From name and Reply-To: the hotel's, else the business's, else the platform's (overrides win). */
    public From senderFor(String businessId, String hotelId, String fromName, String replyTo) {
        Optional<TenantClient.Sender> hotel = tenants.hotel(hotelId);
        Optional<TenantClient.Sender> business = hotel.isPresent() ? Optional.empty() : tenants.business(businessId);
        String name = notBlank(fromName) ? fromName.trim()
                : hotel.map(TenantClient.Sender::name).or(() -> business.map(TenantClient.Sender::name)).filter(MailDispatcher::notBlank).orElse(defaultName);
        String reply = notBlank(replyTo) ? replyTo.trim()
                : hotel.map(TenantClient.Sender::email).or(() -> business.map(TenantClient.Sender::email)).orElse(null);
        if (!notBlank(reply) && hotel.isPresent()) {
            reply = tenants.business(businessId).map(TenantClient.Sender::email).orElse(null);
        }
        return new From(name, notBlank(reply) ? reply : null);
    }

    /** Records the delivery and, for an email, sends it on the worker after the record commits. Never throws. */
    public DeliveryRecord accept(DeliveryRequestedEvent e) {
        // A delivery asked for long ago (a replayed topic, a new consumer group reading from the start) is never sent:
        // the temporary password or link in it is stale, and the person already saw the action's answer at the time.
        if (e.getOccurredAt() != null && e.getOccurredAt().isBefore(Instant.now().minus(maxEventAgeMinutes, ChronoUnit.MINUTES))) {
            log.info("Delivery {} to {} from {} is older than {} minutes; not sent", e.getKind(), e.getTo(), e.getOccurredAt(), maxEventAgeMinutes);
            return null;
        }
        try {
            String channel = e.getChannel() == null ? "EMAIL" : e.getChannel().toUpperCase();
            boolean email = "EMAIL".equals(channel);
            boolean text = !email && texts != null && texts.live(channel);
            From sender = email || text ? senderFor(e.getBusinessId(), e.getHotelId(), e.getFromName(), e.getReplyTo()) : new From(null, null);
            DeliveryRecord.Status status = !email ? (text ? DeliveryRecord.Status.QUEUED : DeliveryRecord.Status.NOT_CONNECTED)
                    : !enabled ? DeliveryRecord.Status.DISABLED : DeliveryRecord.Status.QUEUED;
            DeliveryRecord saved = asSystem(() -> tx.execute(s -> {
                if (e.getEventId() != null) {
                    Optional<DeliveryRecord> existing = records.findByEventId(e.getEventId());
                    if (existing.isPresent()) {
                        return null;
                    }
                }
                return records.save(DeliveryRecord.builder().eventId(e.getEventId()).businessId(e.getBusinessId()).hotelId(e.getHotelId())
                        .channel(channel).toAddress(cut(e.getTo() == null ? "" : e.getTo().trim(), 255)).subject(cut(e.getSubject(), 300))
                        .kind(cut(e.getKind(), 40)).ref(cut(e.getRef(), 80)).fromName(cut(sender.name(), 200)).replyTo(cut(sender.replyTo(), 255))
                        .status(status).build());
            }));
            if (saved == null) {
                log.info("Delivery event {} already recorded; not sent again", e.getEventId());
                return null;
            }
            if (status == DeliveryRecord.Status.QUEUED && text) {
                // @hotel in a WhatsApp template's values is the sending hotel's name, as an email's From name.
                List<String> params = e.getParams() == null ? null
                        : e.getParams().stream().map(p -> "@hotel".equals(p) ? sender.name() : p == null ? "" : p).toList();
                String id = saved.getId();
                String to = saved.getToAddress();
                String body = e.getText() == null ? "" : e.getText().replace("@hotel", sender.name() == null ? "Hospio360" : sender.name());
                afterCommit(() -> executor.execute(() -> deliverText(id, channel, to, body, e.getTemplate(), params)));
            } else if (status == DeliveryRecord.Status.QUEUED) {
                EmailTemplates.Rendered r = templates.render(e.getKind(), sender.name(), e.getSubject(), e.getText());
                String html = notBlank(e.getHtml()) ? e.getHtml() : r.html();
                SmtpGateway.Email mail = new SmtpGateway.Email(fromAddress, sender.name(), saved.getToAddress(), sender.replyTo(), r.subject(), html, r.text());
                String id = saved.getId();
                afterCommit(() -> executor.execute(() -> deliver(id, mail)));
            } else {
                follow(saved);
            }
            return saved;
        } catch (RuntimeException ex) {
            log.error("Delivery {} to {} could not be recorded: {}", e.getKind(), e.getTo(), ex.getMessage());
            return null;
        }
    }

    /** Sends one email: one retry on a transient failure, then the answer goes on the record. */
    void deliver(String recordId, SmtpGateway.Email mail) {
        int attempts = 0;
        SmtpGateway.Accepted accepted = null;
        String error = null;
        while (attempts < 2) {
            attempts++;
            try {
                accepted = smtp.send(mail);
                error = null;
                break;
            } catch (SmtpGateway.SendFailure f) {
                error = f.getMessage();
                if (!f.isTransient() || attempts >= 2) {
                    break;
                }
                log.warn("Email to {} failed ({}); trying once more", mail.to(), error);
                sleep(retryDelayMs);
            } catch (RuntimeException ex) {
                error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                break;
            }
        }
        int tries = attempts;
        SmtpGateway.Accepted ok = accepted;
        String err = error;
        if (ok != null) {
            log.info("Email to {} accepted: {} {}", mail.to(), ok.messageId(), ok.reply());
        } else {
            log.error("Email to {} failed after {} attempt(s): {}", mail.to(), tries, err);
        }
        try {
            asSystem(() -> tx.execute(s -> {
                DeliveryRecord r = records.findById(recordId).orElse(null);
                if (r == null) {
                    return null;
                }
                r.setAttempts(tries);
                if (ok != null) {
                    r.setStatus(DeliveryRecord.Status.SENT);
                    r.setSentAt(Instant.now());
                    r.setProviderMessageId(cut(ok.messageId() + (ok.reply() == null ? "" : " · " + ok.reply()), 255));
                    r.setError(null);
                } else {
                    r.setStatus(DeliveryRecord.Status.FAILED);
                    r.setError(cut(err, 500));
                }
                records.save(r);
                follow(r);
                return null;
            }));
        } catch (RuntimeException ex) {
            log.error("Could not record the answer for delivery {}: {}", recordId, ex.getMessage());
        }
    }

    /** Sends one SMS or WhatsApp (a WhatsApp without an approved template goes as an SMS) and records the answer. */
    void deliverText(String recordId, String channel, String to, String text, String template, List<String> params) {
        com.hospio.messaging.text.TextSender.Outcome out;
        try {
            out = texts.send(channel, to, text, template, params);
        } catch (RuntimeException ex) {
            out = new com.hospio.messaging.text.TextSender.Outcome(false, channel, null, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        com.hospio.messaging.text.TextSender.Outcome done = out;
        if (done.sent()) {
            log.info("{} to {} sent as {} {}{}", channel, to, done.channel(), done.providerRef(), done.note() == null ? "" : " (" + done.note() + ")");
        } else {
            log.warn("{} to {} not sent: {}", channel, to, done.note());
        }
        try {
            asSystem(() -> tx.execute(s -> {
                DeliveryRecord r = records.findById(recordId).orElse(null);
                if (r == null) {
                    return null;
                }
                r.setAttempts(r.getAttempts() + 1);
                if (done.sent()) {
                    r.setStatus(DeliveryRecord.Status.SENT);
                    r.setSentAt(Instant.now());
                    r.setProviderMessageId(cut(done.providerRef(), 255));
                    // Sent, but not the way asked (a WhatsApp that went as an SMS): the reason stays on the record.
                    r.setError(cut(done.note(), 500));
                } else {
                    r.setStatus(DeliveryRecord.Status.FAILED);
                    r.setError(cut(done.note(), 500));
                }
                records.save(r);
                follow(r);
                return null;
            }));
        } catch (RuntimeException ex) {
            log.error("Could not record the answer for delivery {}: {}", recordId, ex.getMessage());
        }
    }

    /**
     * Infobip's final word on an SMS or WhatsApp (docs/sms-whatsapp.md): DELIVERED, or FAILED with the network's reason
     * ("Rejected - Destination on DND list"). {@code group} is Infobip's status group (PENDING, DELIVERED, UNDELIVERABLE,
     * REJECTED, EXPIRED); PENDING changes nothing. Unknown message ids are ignored. Returns whether a record changed.
     */
    public boolean applyReport(String providerRef, String group, String reason) {
        if (providerRef == null || group == null) {
            return false;
        }
        String g = group.toUpperCase(java.util.Locale.ROOT);
        if (!g.equals("DELIVERED") && !g.equals("UNDELIVERABLE") && !g.equals("REJECTED") && !g.equals("EXPIRED")) {
            return false;
        }
        try {
            Boolean changed = asSystem(() -> tx.execute(s -> {
                DeliveryRecord r = records.findFirstByProviderMessageId(providerRef).orElse(null);
                if (r == null) {
                    return false;
                }
                if (g.equals("DELIVERED")) {
                    r.setStatus(DeliveryRecord.Status.DELIVERED);
                    r.setDeliveredAt(Instant.now());
                } else {
                    r.setStatus(DeliveryRecord.Status.FAILED);
                    String was = r.getError();
                    r.setError(cut((was == null || was.isBlank() ? "" : was + "; ") + reason, 500));
                }
                records.save(r);
                follow(r);
                return true;
            }));
            if (Boolean.TRUE.equals(changed)) {
                log.info("Delivery report {}: {} {}", providerRef, g, reason == null ? "" : reason);
            }
            return Boolean.TRUE.equals(changed);
        } catch (RuntimeException ex) {
            log.warn("Delivery report for {} not applied: {}", providerRef, ex.getMessage());
            return false;
        }
    }

    /** A guest message's status follows its delivery (ref = the message id). */
    private void follow(DeliveryRecord r) {
        if (!"MESSAGE".equals(r.getKind()) || r.getRef() == null) {
            return;
        }
        asSystem(() -> tx.execute(s -> {
            messages.findById(r.getRef()).filter(m -> m.getDirection() == Message.Direction.OUT).ifPresent(m -> {
                m.setStatus(r.getStatus().name());
                m.setError(r.getError());
                m.setProviderRef(cut(r.getProviderMessageId(), 100));
                messages.save(m);
            });
            return null;
        }));
    }

    /** A record still QUEUED after 15 minutes lost its worker (a restart): it is marked FAILED, not left waiting. */
    @Scheduled(fixedDelayString = "${hospio.mail.sweep-interval-ms:300000}", initialDelay = 60000)
    public void sweep() {
        try {
            asSystem(() -> tx.execute(s -> {
                for (DeliveryRecord r : records.findTop100ByStatusAndCreatedAtBefore(DeliveryRecord.Status.QUEUED, Instant.now().minus(15, ChronoUnit.MINUTES))) {
                    r.setStatus(DeliveryRecord.Status.FAILED);
                    r.setError("Interrupted before it was sent (service restarted)");
                    records.save(r);
                }
                return null;
            }));
        } catch (RuntimeException ex) {
            log.warn("Delivery sweep failed: {}", ex.getMessage());
        }
    }

    /** Runs after the surrounding transaction commits (now, when there is none), so the worker always finds the record. */
    private static void afterCommit(Runnable work) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            work.run();
                        }
                    });
        } else {
            work.run();
        }
    }

    private static <T> T asSystem(java.util.function.Supplier<T> work) {
        Optional<Tenant> before = TenantContext.current();
        try {
            TenantContext.set(Tenant.system("mail"));
            return work.get();
        } finally {
            if (before.isPresent()) {
                TenantContext.set(before.get());
            } else {
                TenantContext.clear();
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}

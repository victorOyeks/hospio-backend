package com.hospio.messaging.mail;

import com.hospio.common.kafka.events.DeliveryRequestedEvent;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.messaging.client.TenantClient;
import com.hospio.messaging.entity.DeliveryRecord;
import com.hospio.messaging.entity.Message;
import com.hospio.messaging.repository.DeliveryRecordRepository;
import com.hospio.messaging.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one sender (docs/email.md). Every delivery is recorded, and the {@code deliveries} table is the send queue: {@link #accept}
 * only writes the row (QUEUED with what to send) and returns, so the Kafka listener never waits on SMTP or another service
 * and nothing waits in memory. Workers claim due rows with {@code FOR UPDATE SKIP LOCKED} (any number of instances, no row
 * twice), lease them for a few minutes, send outside any transaction, and write the answer (Message-ID and reply line, or the
 * error) on the record and, for a guest message, on the message. A transient failure is retried with back-off; a worker that
 * dies mid-send lets its lease run out and the row is picked up again. Nothing here throws back to whoever asked: the action
 * that wanted the email has already succeeded. SMS and WhatsApp go the same way through
 * {@link com.hospio.messaging.text.TextSender} (Infobip, docs/sms-whatsapp.md) when switched on, else are recorded as NOT_CONNECTED.
 */
@Slf4j
@Service
public class MailDispatcher {

    /** Kinds whose content goes stale (a code, a temporary password, a reset or invite link): never sent once old. */
    public static final Set<String> EXPIRING_KINDS = Set.of("PHONE_CODE", "TEMP_PASSWORD", "PASSWORD_RESET", "INVITE");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BATCH = 20;
    private static final int MAX_ROUNDS = 50;
    /** How long a claimed row is a worker's own; longer than any send (SMTP and Infobip time out within a minute). */
    private static final Duration LEASE = Duration.ofMinutes(5);

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
    private final int maxAttempts;
    private final long maxBacklogHours;
    private com.hospio.messaging.text.TextSender texts;
    private com.hospio.messaging.client.HotelBrandClient brands;
    private com.hospio.messaging.billing.CommBilling billing;
    /**
     * Two sender threads; a "kick" asks one of them to drain the queue. At most one kick waits (the queue holds one), the
     * rest are dropped: a waiting drain will find their rows anyway, and the poll picks up anything left.
     */
    private Executor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), r -> {
        Thread t = new Thread(r, "mail-sender");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.DiscardPolicy());

    public MailDispatcher(DeliveryRecordRepository records, MessageRepository messages, TenantClient tenants, EmailTemplates templates,
                          SmtpGateway smtp, TransactionTemplate tx,
                          @Value("${hospio.mail.enabled:false}") boolean enabled,
                          @Value("${hospio.mail.from-address:no-reply@hospio360.com}") String fromAddress,
                          @Value("${hospio.mail.from-name-default:Hospio360}") String defaultName,
                          @Value("${hospio.mail.retry-delay-ms:5000}") long retryDelayMs,
                          @Value("${hospio.mail.max-event-age-minutes:30}") long maxEventAgeMinutes,
                          @Value("${hospio.mail.max-attempts:3}") int maxAttempts,
                          @Value("${hospio.mail.max-backlog-hours:48}") long maxBacklogHours) {
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
        this.maxAttempts = Math.max(1, maxAttempts);
        this.maxBacklogHours = maxBacklogHours;
    }

    /** SMS and WhatsApp (absent in the email-only tests: they are then recorded as NOT_CONNECTED). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTexts(com.hospio.messaging.text.TextSender texts) {
        this.texts = texts;
    }

    /** The hotel's colour and logo for its emails (absent in tests: Hospio360's own look). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBrands(com.hospio.messaging.client.HotelBrandClient brands) {
        this.brands = brands;
    }

    /** Guest-message billing (docs/guest-message-billing.md); absent in tests: nothing is billable. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBilling(@org.springframework.context.annotation.Lazy com.hospio.messaging.billing.CommBilling billing) {
        this.billing = billing;
    }

    /** Tests drain the queue on the calling thread. */
    void runInline() {
        this.executor = Runnable::run;
    }

    /** Tests: who runs a drain (a no-op executor leaves rows queued). */
    void runOn(Executor executor) {
        this.executor = executor;
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

    /**
     * Records the delivery and returns: an email (or a live SMS / WhatsApp) is QUEUED with what to send, and a worker sends it
     * after this commits. No call to another service happens here (the From name and brand are looked up by the worker), so a
     * listener transaction never waits on HTTP. Never throws.
     */
    public DeliveryRecord accept(DeliveryRequestedEvent e) {
        if (tooOld(e.getKind(), e.getOccurredAt())) {
            // A code, temporary password or link asked for long ago (a replayed topic, a new consumer group reading from the
            // start) is never sent: it is stale, and the person already saw the action's answer at the time. Other kinds (a
            // receipt, an invoice) still go after consumer lag, up to max-backlog-hours.
            log.info("Delivery {} to {} from {} is too old to send; not sent", e.getKind(), e.getTo(), e.getOccurredAt());
            return null;
        }
        try {
            String channel = e.getChannel() == null ? "EMAIL" : e.getChannel().toUpperCase();
            boolean email = "EMAIL".equals(channel);
            boolean text = !email && texts != null && texts.live(channel);
            DeliveryRecord.Status wanted = !email ? (text ? DeliveryRecord.Status.QUEUED : DeliveryRecord.Status.NOT_CONNECTED)
                    : !enabled ? DeliveryRecord.Status.DISABLED : DeliveryRecord.Status.QUEUED;
            // A message the hotel pays for goes only while its guest-message service is on and not suspended.
            com.hospio.messaging.billing.CommBilling.Gate gate = billing == null ? com.hospio.messaging.billing.CommBilling.Gate.FREE
                    : billing.gate(e.getHotelId(), e.getKind());
            DeliveryRecord.Status status = gate.billable() && !gate.allowed() ? DeliveryRecord.Status.SKIPPED : wanted;
            if (status == DeliveryRecord.Status.SKIPPED) {
                log.info("{} {} to {} not sent: {}", channel, e.getKind(), e.getTo(), gate.reason());
            }
            boolean queued = status == DeliveryRecord.Status.QUEUED;
            String params = queued && text && e.getParams() != null ? json(e.getParams()) : null;
            DeliveryRecord saved = asSystem(() -> tx.execute(s -> {
                if (e.getEventId() != null) {
                    Optional<DeliveryRecord> existing = records.findByEventId(e.getEventId());
                    if (existing.isPresent()) {
                        return null;
                    }
                }
                // from_name / reply_to hold the caller's overrides until the worker resolves the hotel's own.
                return records.save(DeliveryRecord.builder().eventId(e.getEventId()).businessId(e.getBusinessId()).hotelId(e.getHotelId())
                        .channel(channel).toAddress(cut(e.getTo() == null ? "" : e.getTo().trim(), 255)).subject(cut(e.getSubject(), 300))
                        .kind(cut(e.getKind(), 40)).ref(cut(e.getRef(), 80)).fromName(cut(trimmed(e.getFromName()), 200))
                        .replyTo(cut(trimmed(e.getReplyTo()), 255)).status(status).billable(gate.billable() && gate.allowed())
                        .error(gate.allowed() ? null : cut(gate.reason(), 500))
                        .nextAttemptAt(queued ? Instant.now() : null).bodyText(queued ? nz(e.getText()) : null)
                        .bodyHtml(queued && email && notBlank(e.getHtml()) ? e.getHtml() : null)
                        .waTemplate(queued && text ? cut(e.getTemplate(), 100) : null).waParams(params).build());
            }));
            if (saved == null) {
                log.info("Delivery event {} already recorded; not sent again", e.getEventId());
                return null;
            }
            if (queued) {
                afterCommit(this::kick);
            } else {
                follow(saved);
            }
            return saved;
        } catch (RuntimeException ex) {
            log.error("Delivery {} to {} could not be recorded: {}", e.getKind(), e.getTo(), ex.getMessage());
            return null;
        }
    }

    /** Codes, passwords and links expire after max-event-age-minutes; anything else after max-backlog-hours (a replayed topic). */
    private boolean tooOld(String kind, Instant at) {
        if (at == null) {
            return false;
        }
        Duration limit = kind != null && EXPIRING_KINDS.contains(kind.toUpperCase(java.util.Locale.ROOT)) ? Duration.ofMinutes(maxEventAgeMinutes)
                : Duration.ofHours(maxBacklogHours);
        return at.isBefore(Instant.now().minus(limit));
    }

    /** Asks a sender thread to drain the queue (dropped when one is already waiting to). */
    void kick() {
        try {
            executor.execute(this::drain);
        } catch (RuntimeException ex) {
            log.warn("Could not start a sender: {}", ex.getMessage());
        }
    }

    /** Every few seconds: anything due (a retry after its back-off, a lease that ran out, rows written by another instance). */
    @Scheduled(fixedDelayString = "${hospio.mail.poll-interval-ms:5000}", initialDelayString = "${hospio.mail.poll-initial-delay-ms:15000}")
    public void poll() {
        kick();
    }

    /** Claims due deliveries a batch at a time and sends them, until none is due. Safe on any number of threads and instances. */
    public void drain() {
        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                List<String> ids = claim();
                if (ids.isEmpty()) {
                    return;
                }
                for (String id : ids) {
                    process(id);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Delivery queue not drained: {}", ex.getMessage());
        }
    }

    /** A short transaction: lock up to a batch of due rows (skipping rows others hold) and lease them to this worker. */
    private List<String> claim() {
        List<String> ids = asSystem(() -> tx.execute(s -> {
            Instant now = Instant.now();
            List<String> due = records.lockDue(now, BATCH);
            if (due != null && !due.isEmpty()) {
                records.lease(due, now.plus(LEASE));
            }
            return due;
        }));
        return ids == null ? List.of() : ids;
    }

    /** What one send came to. */
    private record Result(boolean sent, boolean retry, String via, String providerRef, String error, From from) {
    }

    /** Sends one claimed delivery (outside any transaction) and writes the answer. */
    void process(String recordId) {
        DeliveryRecord r;
        try {
            r = asSystem(() -> records.findById(recordId)).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("Delivery {} could not be read: {}", recordId, ex.getMessage());
            return;
        }
        if (r == null || r.getStatus() != DeliveryRecord.Status.QUEUED) {
            return;
        }
        Result result;
        if (r.getBodyText() == null) {
            result = new Result(false, false, null, null, "Interrupted before it was sent (service restarted)", null);
        } else if (r.getKind() != null && EXPIRING_KINDS.contains(r.getKind()) && r.getCreatedAt() != null
                && r.getCreatedAt().isBefore(Instant.now().minus(Duration.ofMinutes(maxEventAgeMinutes)))) {
            result = new Result(false, false, null, null, "Not sent: still waiting after " + maxEventAgeMinutes + " minutes, the code or link is stale", null);
        } else if (r.getAttempts() > maxAttempts) {
            result = new Result(false, false, null, null, cut(nz(r.getError()) + (notBlank(r.getError()) ? "; " : "") + "gave up after "
                    + (r.getAttempts() - 1) + " attempt(s)", 500), null);
        } else if ("EMAIL".equals(r.getChannel())) {
            result = sendEmail(r);
        } else {
            result = sendText(r);
        }
        finish(recordId, result);
    }

    private Result sendEmail(DeliveryRecord r) {
        From sender;
        SmtpGateway.Email mail;
        try {
            sender = senderFor(r.getBusinessId(), r.getHotelId(), r.getFromName(), r.getReplyTo());
            EmailTemplates.Rendered rendered = templates.render(r.getKind(), sender.name(), r.getSubject(), r.getBodyText(),
                    brands == null ? EmailTemplates.Brand.HOSPIO : brands.brand(r.getHotelId()));
            String html = notBlank(r.getBodyHtml()) ? r.getBodyHtml() : rendered.html();
            mail = new SmtpGateway.Email(fromAddress, sender.name(), r.getToAddress(), sender.replyTo(), rendered.subject(), html, rendered.text());
        } catch (RuntimeException ex) {
            return new Result(false, false, null, null, ex.getClass().getSimpleName() + ": " + ex.getMessage(), null);
        }
        try {
            SmtpGateway.Accepted ok = smtp.send(mail);
            log.info("Email to {} accepted: {} {}", mail.to(), ok.messageId(), ok.reply());
            return new Result(true, false, "EMAIL", ok.messageId() + (ok.reply() == null ? "" : " · " + ok.reply()), null, sender);
        } catch (SmtpGateway.SendFailure f) {
            boolean retry = f.isTransient() && r.getAttempts() < maxAttempts;
            if (retry) {
                log.warn("Email to {} failed ({}); trying again (attempt {} of {})", mail.to(), f.getMessage(), r.getAttempts() + 1, maxAttempts);
            } else {
                log.error("Email to {} failed after {} attempt(s): {}", mail.to(), r.getAttempts(), f.getMessage());
            }
            return new Result(false, retry, null, null, f.getMessage(), sender);
        } catch (RuntimeException ex) {
            log.error("Email to {} failed: {}", mail.to(), ex.getMessage());
            return new Result(false, false, null, null, ex.getClass().getSimpleName() + ": " + ex.getMessage(), sender);
        }
    }

    /** One SMS or WhatsApp (a WhatsApp without an approved template goes as an SMS). */
    private Result sendText(DeliveryRecord r) {
        if (texts == null) {
            return new Result(false, false, null, null, "SMS and WhatsApp are not connected", null);
        }
        try {
            From sender = senderFor(r.getBusinessId(), r.getHotelId(), r.getFromName(), r.getReplyTo());
            // @hotel in a WhatsApp template's values (and the text) is the sending hotel's name, as an email's From name.
            List<String> params = r.getWaParams() == null ? null
                    : readParams(r.getWaParams()).stream().map(p -> "@hotel".equals(p) ? sender.name() : p == null ? "" : p).toList();
            String body = r.getBodyText().replace("@hotel", sender.name() == null ? "Hospio360" : sender.name());
            com.hospio.messaging.text.TextSender.Outcome out = texts.send(r.getChannel(), r.getToAddress(), body, r.getWaTemplate(), params);
            if (out.sent()) {
                log.info("{} to {} sent as {} {}{}", r.getChannel(), r.getToAddress(), out.channel(), out.providerRef(), out.note() == null ? "" : " (" + out.note() + ")");
            } else {
                log.warn("{} to {} not sent: {}", r.getChannel(), r.getToAddress(), out.note());
            }
            // Sent, but not the way asked (a WhatsApp that went as an SMS): the reason stays on the record.
            return new Result(out.sent(), false, out.channel(), out.providerRef(), out.note(), sender);
        } catch (RuntimeException ex) {
            return new Result(false, false, null, null, ex.getClass().getSimpleName() + ": " + ex.getMessage(), null);
        }
    }

    /** Writes the answer, unless the row was finished meanwhile (its lease ran out and another worker got there first). */
    private void finish(String recordId, Result res) {
        try {
            asSystem(() -> tx.execute(s -> {
                DeliveryRecord r = records.findById(recordId).orElse(null);
                if (r == null || r.getStatus() != DeliveryRecord.Status.QUEUED) {
                    return null;
                }
                if (res.from() != null) {
                    r.setFromName(cut(res.from().name(), 200));
                    r.setReplyTo(cut(res.from().replyTo(), 255));
                }
                if (res.retry()) {
                    r.setError(cut(res.error(), 500));
                    r.setNextAttemptAt(Instant.now().plusMillis(backoff(r.getAttempts())));
                    records.save(r);
                    return null;
                }
                if (res.sent()) {
                    r.setStatus(DeliveryRecord.Status.SENT);
                    r.setSentAt(Instant.now());
                    priced(r, res.via());
                    r.setProviderMessageId(cut(res.providerRef(), 255));
                    r.setError(cut(res.error(), 500));
                } else {
                    r.setStatus(DeliveryRecord.Status.FAILED);
                    r.setError(cut(res.error(), 500));
                }
                // Finished: what was sent is not kept (temporary passwords travel in it).
                r.setNextAttemptAt(null);
                r.setBodyText(null);
                r.setBodyHtml(null);
                r.setWaParams(null);
                records.save(r);
                follow(r);
                return null;
            }));
        } catch (RuntimeException ex) {
            // The row stays QUEUED under its lease and is tried again when the lease runs out.
            log.error("Could not record the answer for delivery {}: {}", recordId, ex.getMessage());
        }
    }

    /** retry-delay-ms after the first attempt, then four times longer each time (5 s, 20 s, 80 s…), at most an hour. */
    private long backoff(int attempts) {
        double factor = Math.pow(4, Math.max(0, attempts - 1));
        return (long) Math.min(Duration.ofHours(1).toMillis(), retryDelayMs * factor);
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

    /** How a message went, and (when the hotel pays for it) its price by that channel at the hotel's prices today. */
    private void priced(DeliveryRecord r, String via) {
        r.setSentVia(via);
        if (r.isBillable() && billing != null && r.getHotelId() != null) {
            r.setUnitPrice(billing.price(r.getHotelId(), via));
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

    private static String trimmed(String s) {
        return notBlank(s) ? s.trim() : null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String json(List<String> params) {
        try {
            return JSON.writeValueAsString(params);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException("WhatsApp values could not be kept: " + ex.getMessage());
        }
    }

    private static List<String> readParams(String json) {
        try {
            return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException("WhatsApp values could not be read: " + ex.getMessage());
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}

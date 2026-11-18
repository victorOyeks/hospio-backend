package com.hospio.messaging.service;

import com.hospio.common.integrations.settings.IntegrationTestResult;
import com.hospio.messaging.mail.MailDispatcher;
import com.hospio.messaging.mail.MailRouter;
import com.hospio.messaging.text.InfobipClient;
import com.hospio.messaging.text.TermiiClient;
import com.hospio.messaging.text.TextSender;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The admin portal's connection checks for WhatsApp, email and SMS, as this service sees them (platform-service asks, GET
 * /internal/integrations/status): which provider is in force, whether its keys are there (never the keys), the on/off switches,
 * the sender, and a live read-only call to the provider (the same checks as Settings · Integrations → Test: Infobip's template
 * list or balance, Brevo's account, an SMTP sign-in without sending, Termii's balance), and each channel's last outcomes from the
 * delivery log ("Last message: failed 2 h ago — …", counts over 7 days; ERROR when the newest three all failed; email is read
 * honestly, see {@link #emailOutcomes}). Nothing is ever sent or changed. The live calls run in parallel and each is given {@code timeout}; one that takes longer
 * is reported as not answering.
 */
@Slf4j
@Service
public class ConnectionChecks {

    /** One row: key whatsapp, email or sms; status CONNECTED, NOT_CONFIGURED or ERROR; a sentence or two of what was found. */
    public record ConnectionCheck(String key, String status, String detail) {
    }

    private static final ExecutorService LIVE = Executors.newVirtualThreadPerTaskExecutor();

    private final InfobipClient infobip;
    private final TermiiClient termii;
    private final TextSender texts;
    private final MailRouter mail;
    private final MailDispatcher mailer;
    private final boolean echo;
    private final Duration timeout;

    @Autowired
    public ConnectionChecks(InfobipClient infobip, TermiiClient termii, TextSender texts, MailRouter mail, MailDispatcher mailer,
                            @Value("${hospio.deliveries.echo:false}") boolean echo,
                            @Value("${hospio.integrations.check-timeout-ms:3500}") long timeoutMs) {
        this.infobip = infobip;
        this.termii = termii;
        this.texts = texts;
        this.mail = mail;
        this.mailer = mailer;
        this.echo = echo;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /** The delivery log, read for each channel's last outcomes (absent in the provider-only tests). */
    private com.hospio.messaging.repository.DeliveryRecordRepository deliveries;

    @Autowired(required = false)
    public void setDeliveries(com.hospio.messaging.repository.DeliveryRecordRepository deliveries) {
        this.deliveries = deliveries;
    }

    /** Whether the Brevo and Termii webhooks can be believed (their keys set): without them bounces and SMS reports never come. */
    @Value("${hospio.brevo.webhook-key:}")
    private String brevoWebhookKey = "";
    @Value("${hospio.termii.secret-key:}")
    private String termiiSecretKey = "";
    private com.hospio.common.integrations.settings.IntegrationSettingsReader integrations;

    @Autowired(required = false)
    public void setIntegrations(com.hospio.common.integrations.settings.IntegrationSettingsReader integrations) {
        this.integrations = integrations;
    }

    private boolean brevoEventsOn() {
        var env = new com.hospio.common.integrations.settings.IntegrationSettings.Brevo(null, null, null, brevoWebhookKey);
        return StringUtils.hasText((integrations == null ? env : integrations.effective(env)).webhookKey());
    }

    private boolean termiiReportsOn() {
        var env = new com.hospio.common.integrations.settings.IntegrationSettings.Termii(null, null, null, null, termiiSecretKey);
        return StringUtils.hasText((integrations == null ? env : integrations.effective(env)).secretKey());
    }

    /** How far back the last outcomes are read, how many at most, and how many failures in a row turn a row to ERROR. */
    static final Duration WINDOW = Duration.ofDays(7);
    static final int SAMPLE = 200;
    static final int FAILING_IN_A_ROW = 3;

    /** What the delivery log says about a channel: the last outcome, counts over the window, and whether the newest all failed. */
    record Outcomes(String sentence, boolean failing) {
    }

    /**
     * The channel's last outcome and its record over the last 7 days (at most the newest 200), from the delivery log, read-only.
     * Only the provider's answers count: a message held back by the allow-list, not a phone number, skipped or switched off is
     * not an outcome. {@code failing} when the newest three outcomes all failed.
     */
    Outcomes outcomes(String channel, java.time.Instant now) {
        if (deliveries == null) {
            return new Outcomes("", false);
        }
        if ("EMAIL".equals(channel)) {
            return emailOutcomes(now);
        }
        List<com.hospio.messaging.entity.DeliveryRecord> rows;
        try {
            rows = asSystem(() -> deliveries.latest(channel, List.of(com.hospio.messaging.entity.DeliveryRecord.Status.SENT,
                    com.hospio.messaging.entity.DeliveryRecord.Status.DELIVERED, com.hospio.messaging.entity.DeliveryRecord.Status.FAILED),
                    now.minus(WINDOW), org.springframework.data.domain.PageRequest.of(0, SAMPLE)));
        } catch (RuntimeException e) {
            log.warn("Connection check: the {} delivery log couldn't be read: {}", channel, e.getClass().getSimpleName());
            return new Outcomes(" The delivery log couldn't be read.", false);
        }
        List<com.hospio.messaging.entity.DeliveryRecord> real = rows == null ? List.of() : rows.stream().filter(ConnectionChecks::providerOutcome).toList();
        String what = switch (channel) {
            case "EMAIL" -> "email";
            case "SMS" -> "code";
            default -> "message";
        };
        if (real.isEmpty()) {
            return new Outcomes(" No " + ("SMS".equals(channel) ? "SMS code" : "WHATSAPP".equals(channel) ? "WhatsApp" : "email")
                    + " sent in the last 7 days." + reportsNote(channel), false);
        }
        com.hospio.messaging.entity.DeliveryRecord last = real.get(0);
        StringBuilder out = new StringBuilder(" Last ").append(what).append(": ").append(word(last)).append(' ')
                .append(ago(last, now));
        if (last.getStatus() == com.hospio.messaging.entity.DeliveryRecord.Status.FAILED && StringUtils.hasText(last.getError())) {
            out.append(" — ").append(cut(last.getError().trim().replaceAll("\\.$", ""), 140));
        }
        out.append('.');
        long delivered = real.stream().filter(r -> r.getStatus() == com.hospio.messaging.entity.DeliveryRecord.Status.DELIVERED).count();
        long failed = real.stream().filter(r -> r.getStatus() == com.hospio.messaging.entity.DeliveryRecord.Status.FAILED).count();
        out.append(" Last 7 days: ").append(real.size()).append(real.size() >= SAMPLE ? "+" : "").append(" sent, ")
                .append(delivered).append(" delivered, ").append(failed).append(" failed");
        out.append('.').append(reportsNote(channel));
        boolean failing = real.size() >= FAILING_IN_A_ROW && real.stream().limit(FAILING_IN_A_ROW)
                .allMatch(r -> r.getStatus() == com.hospio.messaging.entity.DeliveryRecord.Status.FAILED);
        if (failing) {
            out.append(" The last ").append(FAILING_IN_A_ROW).append(" all failed.");
        }
        return new Outcomes(out.toString(), failing);
    }

    /** At most this many newest emails are read (test-address rows are many on staging and are dropped after reading). */
    static final int EMAIL_SAMPLE = 1000;
    /** Email is ERROR when, among at least {@link #BOUNCE_MIN} tracked emails to real addresses, this share or more bounced. */
    static final int BOUNCE_ERROR_PERCENT = 10;
    static final int BOUNCE_MIN = 10;
    private static final java.time.format.DateTimeFormatter TRACKED_AT = java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm",
            java.util.Locale.ENGLISH).withZone(java.time.ZoneId.of("Africa/Lagos"));

    /**
     * Email's last outcomes, honestly (docs/integrations.md, "Email health"): emails to test addresses (TestAddresses: .test,
     * example.com…) are never counted; delivered and bounced are counted only for emails recorded since Brevo's tracking started
     * (its first webhook event), because older ones can only ever say "sent". With tracking on and no real email since, the row
     * says so and is not an error. ERROR only when real addresses fail: the newest {@link #FAILING_IN_A_ROW} real emails all
     * failed, or {@link #BOUNCE_ERROR_PERCENT}% or more of at least {@link #BOUNCE_MIN} tracked real emails bounced.
     */
    Outcomes emailOutcomes(java.time.Instant now) {
        List<com.hospio.messaging.entity.DeliveryRecord> rows;
        java.time.Instant since;
        try {
            rows = asSystem(() -> deliveries.latest("EMAIL", List.of(com.hospio.messaging.entity.DeliveryRecord.Status.SENT,
                    com.hospio.messaging.entity.DeliveryRecord.Status.DELIVERED, com.hospio.messaging.entity.DeliveryRecord.Status.FAILED),
                    now.minus(WINDOW), org.springframework.data.domain.PageRequest.of(0, EMAIL_SAMPLE)));
            since = asSystem(deliveries::emailTrackingSince);
        } catch (RuntimeException e) {
            log.warn("Connection check: the EMAIL delivery log couldn't be read: {}", e.getClass().getSimpleName());
            return new Outcomes(" The delivery log couldn't be read.", false);
        }
        List<com.hospio.messaging.entity.DeliveryRecord> all = rows == null ? List.of() : rows.stream().filter(ConnectionChecks::providerOutcome).toList();
        List<com.hospio.messaging.entity.DeliveryRecord> real = all.stream()
                .filter(r -> !com.hospio.messaging.mail.TestAddresses.reserved(r.getToAddress())).toList();
        long toTest = all.size() - real.size();
        String testNote = toTest == 0 ? "" : " " + toTest + (toTest == 1 ? " email" : " emails") + " to test addresses not counted.";
        String more = rows != null && rows.size() >= EMAIL_SAMPLE ? "+" : "";
        var failedStatus = com.hospio.messaging.entity.DeliveryRecord.Status.FAILED;

        if (since == null) {
            // No report has ever come from Brevo: only "sent" and refusals at send time are known.
            if (real.isEmpty()) {
                return new Outcomes(" No email to a real address in the last 7 days." + testNote + reportsNote("EMAIL")
                        + (brevoEventsOn() ? " No delivery report has come from Brevo yet." : ""), false);
            }
            long failed = real.stream().filter(r -> r.getStatus() == failedStatus).count();
            boolean failing = real.size() >= FAILING_IN_A_ROW && real.stream().limit(FAILING_IN_A_ROW).allMatch(r -> r.getStatus() == failedStatus);
            return new Outcomes(lastEmail(real.get(0), now) + " Last 7 days (real addresses): " + real.size() + more + " sent, " + failed
                    + " failed." + testNote + reportsNote("EMAIL") + (brevoEventsOn() ? " No delivery report has come from Brevo yet, so "
                    + "deliveries and bounces aren't counted." : "") + (failing ? " The last " + FAILING_IN_A_ROW + " real emails all failed." : ""),
                    failing);
        }

        String tracking = "Tracking since " + TRACKED_AT.format(since);
        List<com.hospio.messaging.entity.DeliveryRecord> tracked = real.stream()
                .filter(r -> r.getCreatedAt() != null && !r.getCreatedAt().isBefore(since)).toList();
        if (tracked.isEmpty()) {
            String before = real.isEmpty() ? "" : " Before then:" + lastEmail(real.get(0), now).replaceFirst("^ Last email", " last email");
            return new Outcomes(" " + tracking + ". No real emails since delivery tracking started." + before + testNote + reportsNote("EMAIL"),
                    false);
        }
        long delivered = tracked.stream().filter(r -> r.getStatus() == com.hospio.messaging.entity.DeliveryRecord.Status.DELIVERED).count();
        long bounced = tracked.stream().filter(r -> r.getProviderEvent() != null && BOUNCES.contains(r.getProviderEvent())).count();
        long otherFailed = tracked.stream().filter(r -> r.getStatus() == failedStatus
                && !(r.getProviderEvent() != null && BOUNCES.contains(r.getProviderEvent()))).count();
        long rate = Math.round(100.0 * bounced / tracked.size());
        boolean inARow = tracked.size() >= FAILING_IN_A_ROW && tracked.stream().limit(FAILING_IN_A_ROW).allMatch(r -> r.getStatus() == failedStatus);
        boolean bouncing = tracked.size() >= BOUNCE_MIN && rate >= BOUNCE_ERROR_PERCENT;
        StringBuilder out = new StringBuilder(lastEmail(tracked.get(0), now)).append(' ').append(tracking)
                .append("; last 7 days (real addresses): ").append(tracked.size()).append(more).append(" sent, ").append(delivered)
                .append(" delivered, ").append(bounced).append(" bounced (").append(rate).append("%)");
        if (otherFailed > 0) {
            out.append(", ").append(otherFailed).append(" failed");
        }
        out.append('.').append(testNote).append(reportsNote("EMAIL"));
        if (inARow) {
            out.append(" The last ").append(FAILING_IN_A_ROW).append(" real emails all failed.");
        }
        if (bouncing) {
            out.append(" The bounce rate is ").append(BOUNCE_ERROR_PERCENT).append("% or more.");
        }
        return new Outcomes(out.toString(), inARow || bouncing);
    }

    /** " Last email: delivered 5 min ago — reason." (the reason only for a failure). */
    private static String lastEmail(com.hospio.messaging.entity.DeliveryRecord last, java.time.Instant now) {
        StringBuilder out = new StringBuilder(" Last email: ").append(word(last)).append(' ').append(ago(last, now));
        if (last.getStatus() == com.hospio.messaging.entity.DeliveryRecord.Status.FAILED && StringUtils.hasText(last.getError())) {
            out.append(" — ").append(cut(last.getError().trim().replaceAll("\\.$", ""), 140));
        }
        return out.append('.').toString();
    }

    /** Why a channel's outcomes may stay "sent": its provider's reports can't be believed yet. */
    private String reportsNote(String channel) {
        if ("EMAIL".equals(channel) && !brevoEventsOn()) {
            return " Bounces and deliveries aren't reported: the Brevo webhook key isn't set.";
        }
        if ("SMS".equals(channel) && !termiiReportsOn()) {
            return " Termii's delivery reports aren't believed until its secret key is set.";
        }
        return "";
    }

    private static final java.util.Set<String> BOUNCES = java.util.Set.of("hard_bounce", "soft_bounce", "blocked", "invalid_email");

    /** A provider's answer, not our own rule (the staging allow-list, a missing number, no template). */
    private static boolean providerOutcome(com.hospio.messaging.entity.DeliveryRecord r) {
        String e = r.getError() == null ? "" : r.getError();
        return !(r.getStatus() == com.hospio.messaging.entity.DeliveryRecord.Status.FAILED
                && (e.startsWith("Held back") || e.startsWith("Not a phone number") || e.startsWith("Not sent:")
                || e.startsWith("Interrupted before")));
    }

    private static String word(com.hospio.messaging.entity.DeliveryRecord r) {
        return switch (r.getStatus()) {
            case DELIVERED -> "delivered";
            case FAILED -> "failed";
            default -> "sent";
        };
    }

    /** "2 h ago" from the last time the record changed (a report), else when it was sent or made. */
    private static String ago(com.hospio.messaging.entity.DeliveryRecord r, java.time.Instant now) {
        java.time.Instant at = r.getProviderEventAt() != null ? r.getProviderEventAt() : r.getDeliveredAt() != null ? r.getDeliveredAt()
                : r.getSentAt() != null ? r.getSentAt() : r.getCreatedAt();
        if (at == null) {
            return "recently";
        }
        long mins = Math.max(0, Duration.between(at, now).toMinutes());
        return mins < 1 ? "just now" : mins < 60 ? mins + " min ago" : mins < 48 * 60 ? (mins / 60) + " h ago" : (mins / 1440) + " d ago";
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private static <T> T asSystem(Supplier<T> work) {
        java.util.Optional<com.hospio.common.tenancy.Tenant> before = com.hospio.common.tenancy.TenantContext.current();
        try {
            com.hospio.common.tenancy.TenantContext.set(com.hospio.common.tenancy.Tenant.system("connection-checks"));
            return work.get();
        } finally {
            if (before.isPresent()) {
                com.hospio.common.tenancy.TenantContext.set(before.get());
            } else {
                com.hospio.common.tenancy.TenantContext.clear();
            }
        }
    }

    public List<ConnectionCheck> run() {
        InfobipClient.Setup ib = infobip.setup();
        TermiiClient.Setup tm = termii.setup();
        boolean brevo = mail.brevo().configured();
        boolean smtp = mail.smtp().configured();
        boolean smsViaTermii = tm.configured();

        // The live calls first, all at once; each row then waits for its own.
        CompletableFuture<IntegrationTestResult> infobipLive = ib.keyPresent() ? live(infobip::check, "Infobip") : null;
        CompletableFuture<IntegrationTestResult> mailLive = brevo ? live(() -> mail.brevo().check(), "Brevo")
                : smtp ? live(() -> mail.smtp().check(), "The SMTP relay") : null;
        // Termii is asked only when codes go through it, or when it is the only SMS key there is.
        CompletableFuture<IntegrationTestResult> termiiLive = smsViaTermii || (tm.keyPresent() && !ib.keyPresent()) ? live(termii::check, "Termii") : null;

        List<ConnectionCheck> out = new ArrayList<>();
        out.add(whatsApp(ib, infobipLive));
        out.add(email(brevo, smtp, mailLive));
        out.add(sms(ib, tm, smsViaTermii, smsViaTermii || !ib.keyPresent() ? termiiLive : infobipLive));
        return out.stream().map(c -> new ConnectionCheck(c.key(), c.status(), c.detail() + echoNote())).toList();
    }

    private ConnectionCheck whatsApp(InfobipClient.Setup ib, CompletableFuture<IntegrationTestResult> live) {
        boolean on = texts.whatsAppOn();
        if (!ib.keyPresent()) {
            return new ConnectionCheck("whatsapp", "NOT_CONFIGURED", "Infobip has no base URL or API key (saved in the portal or on the server), so no "
                    + "WhatsApp can go out. WhatsApp on: " + yesNo(on) + ".");
        }
        if (!StringUtils.hasText(ib.whatsAppSender())) {
            return new ConnectionCheck("whatsapp", "NOT_CONFIGURED", "Infobip key present, but there is no WhatsApp sender number, so no WhatsApp "
                    + "can go out. " + sentence(wait(live)));
        }
        IntegrationTestResult r = wait(live);
        String head = "Infobip, sender +" + ib.whatsAppSender() + ", key present. ";
        if (!on) {
            return new ConnectionCheck("whatsapp", "NOT_CONFIGURED", head + "WhatsApp is switched off, so nothing is sent. " + sentence(r));
        }
        Outcomes o = outcomes("WHATSAPP", java.time.Instant.now());
        return new ConnectionCheck("whatsapp", r.ok() && !o.failing() ? "CONNECTED" : "ERROR", head + sentence(r) + allowListNote() + o.sentence());
    }

    private ConnectionCheck email(boolean brevo, boolean smtp, CompletableFuture<IntegrationTestResult> live) {
        boolean on = mailer.enabled();
        if (!brevo && !smtp) {
            return new ConnectionCheck("email", "NOT_CONFIGURED", "There is no Brevo API key or SMTP host (saved in the portal or on the server), "
                    + "so no email can go out. Email on: " + yesNo(on) + ".");
        }
        IntegrationTestResult r = wait(live);
        String head = (brevo ? "Brevo API, key present" : "SMTP relay (no Brevo API key)") + ", from " + mailer.fromAddress() + ". ";
        if (!on) {
            return new ConnectionCheck("email", "NOT_CONFIGURED", head + "Email is switched off, so nothing is sent. " + sentence(r));
        }
        Outcomes o = outcomes("EMAIL", java.time.Instant.now());
        return new ConnectionCheck("email", r.ok() && !o.failing() ? "CONNECTED" : "ERROR", head + sentence(r) + o.sentence());
    }

    /** SMS carries one-time sign-in codes only: through Termii when it is on with a key and sender, else Infobip's SMS. */
    private ConnectionCheck sms(InfobipClient.Setup ib, TermiiClient.Setup tm, boolean viaTermii, CompletableFuture<IntegrationTestResult> live) {
        boolean on = texts.smsOn();
        String scope = "Sign-in codes only. ";
        if (!viaTermii && !ib.keyPresent()) {
            String why = tm.enabled() && tm.keyPresent() && !StringUtils.hasText(tm.senderId()) ? "Termii is on but has no sender ID"
                    : tm.enabled() && !tm.keyPresent() ? "Termii is on but has no API key" : "Neither Termii nor Infobip has a key";
            return new ConnectionCheck("sms", "NOT_CONFIGURED", scope + why + ", so no SMS can go out. SMS on: " + yesNo(on) + "."
                    + (live == null ? "" : " " + sentence(wait(live))));
        }
        String head = viaTermii
                ? "Termii, sender ID " + tm.senderId() + ", channel " + tm.channel() + ", key present. "
                : "Infobip SMS, sender " + (StringUtils.hasText(ib.smsSender()) ? ib.smsSender() : "Infobip's default") + ", key present"
                + (tm.enabled() ? " (Termii is on but " + (tm.keyPresent() ? "has no sender ID" : "has no API key") + ")" : " (Termii is off)") + ". ";
        IntegrationTestResult r = wait(live);
        if (!on) {
            return new ConnectionCheck("sms", "NOT_CONFIGURED", scope + head + "SMS is switched off, so no code goes by SMS. " + sentence(r));
        }
        Outcomes o = outcomes("SMS", java.time.Instant.now());
        return new ConnectionCheck("sms", r.ok() && !o.failing() ? "CONNECTED" : "ERROR", scope + head + sentence(r) + allowListNote() + o.sentence());
    }

    private CompletableFuture<IntegrationTestResult> live(Supplier<IntegrationTestResult> check, String who) {
        long secs = timeout.toMillis();
        return CompletableFuture.supplyAsync(check, LIVE)
                .completeOnTimeout(IntegrationTestResult.failed(who + " didn't answer within " + seconds(secs)), secs, TimeUnit.MILLISECONDS)
                .exceptionally(e -> {
                    Throwable cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
                    log.warn("Connection check through {} failed: {}", who, cause.getClass().getSimpleName());
                    return IntegrationTestResult.failed(who + " couldn't be checked: " + cause.getClass().getSimpleName());
                });
    }

    private static IntegrationTestResult wait(CompletableFuture<IntegrationTestResult> f) {
        if (f == null) {
            return IntegrationTestResult.failed("No live check was made");
        }
        IntegrationTestResult r = f.join();
        return r == null ? IntegrationTestResult.failed("The provider gave no answer") : r;
    }

    private String allowListNote() {
        int n = texts.allowListSize();
        return n == 0 ? "" : " Only " + n + (n == 1 ? " allow-listed number receives" : " allow-listed numbers receive") + " texts on this server.";
    }

    private String echoNote() {
        return echo ? " Echo on: each message is also returned to the app and shown on screen." : " Echo off.";
    }

    private static String sentence(IntegrationTestResult r) {
        String m = r.message() == null ? "" : r.message().trim();
        return m.isEmpty() ? "" : m.endsWith(".") ? m : m + ".";
    }

    private static String yesNo(boolean b) {
        return b ? "yes" : "no";
    }

    private static String seconds(long ms) {
        return (ms % 1000 == 0 ? String.valueOf(ms / 1000) : String.valueOf(ms / 1000.0)) + " s";
    }
}

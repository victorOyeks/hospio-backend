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
 * list or balance, Brevo's account, an SMTP sign-in without sending, Termii's balance). Nothing is ever sent. The live calls run
 * in parallel and each is given {@code timeout}; one that takes longer is reported as not answering.
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
        return new ConnectionCheck("whatsapp", r.ok() ? "CONNECTED" : "ERROR", head + sentence(r) + allowListNote());
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
        return new ConnectionCheck("email", r.ok() ? "CONNECTED" : "ERROR", head + sentence(r));
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
        return new ConnectionCheck("sms", r.ok() ? "CONNECTED" : "ERROR", scope + head + sentence(r) + allowListNote());
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

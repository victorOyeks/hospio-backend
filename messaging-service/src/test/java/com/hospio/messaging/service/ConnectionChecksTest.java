package com.hospio.messaging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.integrations.settings.IntegrationTestResult;
import com.hospio.messaging.StubHttp;
import com.hospio.messaging.mail.BrevoApiGateway;
import com.hospio.messaging.mail.JavaMailGateway;
import com.hospio.messaging.mail.MailDispatcher;
import com.hospio.messaging.mail.MailRouter;
import com.hospio.messaging.service.ConnectionChecks.ConnectionCheck;
import com.hospio.messaging.text.InfobipClient;
import com.hospio.messaging.text.TermiiClient;
import com.hospio.messaging.text.TextSender;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The admin portal's connection checks: what messaging-service sees for WhatsApp, email and SMS. Read-only: nothing is ever sent. */
class ConnectionChecksTest {

    private static final String INFOBIP_KEY = "ib-secret-4f1c9a7e2b";
    private static final String TERMII_KEY = "tm-secret-8d2e6b1f0c";
    private static final String BREVO_KEY = "xkeysib-secret-77aa11";

    private StubHttp server;
    private MailDispatcher mailer;
    private JavaMailGateway smtp;

    @BeforeEach
    void start() throws Exception {
        server = new StubHttp()
                .on("/whatsapp/2/senders/2348148849797/templates", 200,
                        "{\"templates\":[{\"name\":\"otp\",\"status\":\"APPROVED\"},{\"name\":\"welcome\",\"status\":\"PENDING\"}]}")
                .on("/api/get-balance", 200, "{\"user\":\"Oyeks\",\"balance\":1520.5,\"currency\":\"NGN\"}")
                .on("/account", 200, "{\"email\":\"ops@hospio360.com\",\"companyName\":\"Hospio360\"}");
        mailer = mock(MailDispatcher.class);
        when(mailer.enabled()).thenReturn(true);
        when(mailer.fromAddress()).thenReturn("no-reply@hospio360.com");
        smtp = mock(JavaMailGateway.class);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private MailRouter mail(String brevoKey) {
        MailRouter router = mock(MailRouter.class);
        when(router.brevo()).thenReturn(new BrevoApiGateway(brevoKey, server.url()));
        when(router.smtp()).thenReturn(smtp);
        return router;
    }

    private static Map<String, ConnectionCheck> byKey(List<ConnectionCheck> rows) {
        return rows.stream().collect(Collectors.toMap(ConnectionCheck::key, Function.identity()));
    }

    @Test
    void everythingOnAndAnsweringIsConnectedWithWhatIsInForceAndNeverAKey() {
        InfobipClient infobip = new InfobipClient(server.url(), INFOBIP_KEY, "", "+2348148849797", "", "", "");
        TermiiClient termii = new TermiiClient(true, server.url(), TERMII_KEY, "OE Alert", "dnd");
        TextSender texts = new TextSender(infobip, termii, true, true, "en", "2348000000001,2348000000002");

        List<ConnectionCheck> rows = new ConnectionChecks(infobip, termii, texts, mail(BREVO_KEY), mailer, false, 3000).run();

        assertThat(rows).extracting(ConnectionCheck::key).containsExactly("whatsapp", "email", "sms");
        Map<String, ConnectionCheck> r = byKey(rows);
        assertThat(r.get("whatsapp").status()).isEqualTo("CONNECTED");
        assertThat(r.get("whatsapp").detail()).contains("Infobip, sender +2348148849797, key present", "2 templates, 1 approved",
                "Only 2 allow-listed numbers receive texts", "Echo off");
        assertThat(r.get("email").status()).isEqualTo("CONNECTED");
        assertThat(r.get("email").detail()).contains("Brevo API, key present", "from no-reply@hospio360.com", "Brevo account OK");
        assertThat(r.get("sms").status()).isEqualTo("CONNECTED");
        assertThat(r.get("sms").detail()).contains("Sign-in codes only", "Termii, sender ID OE Alert, channel dnd, key present", "balance 1520.5 NGN");
        String all = rows.toString();
        assertThat(all).doesNotContain(INFOBIP_KEY, TERMII_KEY, BREVO_KEY);
        // Read-only: only the template list, Termii's balance and Brevo's account were asked; no send endpoint.
        assertThat(server.seen()).allSatisfy(s -> assertThat(s.method()).isEqualTo("GET"));
        assertThat(server.seen()).extracting(StubHttp.Seen::uri).noneMatch(u -> u.contains("/api/sms/send") || u.contains("/smtp/email") || u.contains("/whatsapp/1/message") || u.contains("/sms/2/text"));
    }

    @Test
    void aRefusedKeyIsAnErrorWithTheProvidersWordsButNotTheKey() {
        server.on("/api/get-balance", 401, "{\"message\":\"Invalid API key " + TERMII_KEY.substring(0, 4) + "\"}")
                .on("/account", 401, "{\"code\":\"unauthorized\",\"message\":\"Key not found: " + BREVO_KEY + "\"}");
        InfobipClient infobip = new InfobipClient(server.url(), INFOBIP_KEY, "", "+2348148849797", "", "", "");
        TermiiClient termii = new TermiiClient(true, server.url(), TERMII_KEY, "OE Alert", "dnd");
        TextSender texts = new TextSender(infobip, termii, true, true, "en", "");

        Map<String, ConnectionCheck> r = byKey(new ConnectionChecks(infobip, termii, texts, mail(BREVO_KEY), mailer, false, 3000).run());

        assertThat(r.get("sms").status()).isEqualTo("ERROR");
        assertThat(r.get("sms").detail()).contains("Termii refused the key (HTTP 401)");
        assertThat(r.get("email").status()).isEqualTo("ERROR");
        assertThat(r.get("email").detail()).contains("Brevo refused the key").doesNotContain(BREVO_KEY);
        assertThat(r.get("whatsapp").status()).isEqualTo("CONNECTED");
    }

    @Test
    void switchedOffOrMissingKeysIsNotConfiguredAndSaysWhy() {
        InfobipClient infobip = new InfobipClient("", "", "", "", "", "", "");
        TermiiClient termii = new TermiiClient(false, server.url(), "", "", "dnd");
        TextSender texts = new TextSender(infobip, termii, false, false, "en", "");
        when(mailer.enabled()).thenReturn(false);

        Map<String, ConnectionCheck> r = byKey(new ConnectionChecks(infobip, termii, texts, mail(""), mailer, true, 3000).run());

        assertThat(r.get("whatsapp").status()).isEqualTo("NOT_CONFIGURED");
        assertThat(r.get("whatsapp").detail()).contains("Infobip has no base URL or API key", "WhatsApp on: no");
        assertThat(r.get("email").status()).isEqualTo("NOT_CONFIGURED");
        assertThat(r.get("email").detail()).contains("no Brevo API key or SMTP host");
        assertThat(r.get("sms").status()).isEqualTo("NOT_CONFIGURED");
        assertThat(r.get("sms").detail()).contains("Neither Termii nor Infobip has a key");
        assertThat(r.values()).allSatisfy(c -> assertThat(c.detail()).contains("Echo on: each message is also returned to the app"));
        assertThat(server.seen()).isEmpty();
    }

    @Test
    void keysPresentButSwitchedOffIsNotConfiguredAndStillChecksTheKey() {
        InfobipClient infobip = new InfobipClient(server.url(), INFOBIP_KEY, "", "+2348148849797", "", "", "");
        TermiiClient termii = new TermiiClient(false, server.url(), TERMII_KEY, "", "dnd");
        TextSender texts = new TextSender(infobip, termii, false, false, "en", "");

        Map<String, ConnectionCheck> r = byKey(new ConnectionChecks(infobip, termii, texts, mail(BREVO_KEY), mailer, false, 3000).run());

        assertThat(r.get("whatsapp").status()).isEqualTo("NOT_CONFIGURED");
        assertThat(r.get("whatsapp").detail()).contains("WhatsApp is switched off, so nothing is sent", "1 approved");
        // Termii off: codes would go through Infobip's SMS, which is switched off.
        assertThat(r.get("sms").status()).isEqualTo("NOT_CONFIGURED");
        assertThat(r.get("sms").detail()).contains("Infobip SMS, sender Infobip's default", "(Termii is off)", "SMS is switched off");
    }

    @Test
    void aSlowProviderIsAnErrorForItsRowOnlyAndTheOthersStillAnswer() {
        InfobipClient infobip = new InfobipClient(server.url(), INFOBIP_KEY, "", "+2348148849797", "", "", "");
        TermiiClient termii = mock(TermiiClient.class);
        when(termii.setup()).thenReturn(new TermiiClient.Setup(true, true, "OE Alert", "dnd", true));
        when(termii.check()).thenAnswer(a -> {
            Thread.sleep(3000);
            return IntegrationTestResult.ok("late");
        });
        TextSender texts = new TextSender(infobip, termii, true, true, "en", "");

        long t0 = System.nanoTime();
        Map<String, ConnectionCheck> r = byKey(new ConnectionChecks(infobip, termii, texts, mail(BREVO_KEY), mailer, false, 300).run());
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(r.get("sms").status()).isEqualTo("ERROR");
        assertThat(r.get("sms").detail()).contains("Termii didn't answer within 0.3 s");
        assertThat(r.get("whatsapp").status()).isEqualTo("CONNECTED");
        assertThat(r.get("email").status()).isEqualTo("CONNECTED");
        assertThat(ms).isLessThan(2000);
        verify(termii, never()).sms(any(), any());
    }

    @Test
    void aCheckThatThrowsIsAnErrorWithItsKind() {
        InfobipClient infobip = new InfobipClient(server.url(), INFOBIP_KEY, "", "+2348148849797", "", "", "");
        TermiiClient termii = new TermiiClient(false, server.url(), "", "", "dnd");
        TextSender texts = new TextSender(infobip, termii, true, true, "en", "");
        when(smtp.configured()).thenReturn(true);
        when(smtp.check()).thenThrow(new IllegalStateException("boom " + BREVO_KEY));

        Map<String, ConnectionCheck> r = byKey(new ConnectionChecks(infobip, termii, texts, mail(""), mailer, false, 3000).run());

        assertThat(r.get("email").status()).isEqualTo("ERROR");
        assertThat(r.get("email").detail()).contains("SMTP relay (no Brevo API key)", "couldn't be checked: IllegalStateException")
                .doesNotContain(BREVO_KEY);
    }

    private static com.hospio.messaging.entity.DeliveryRecord row(String channel, com.hospio.messaging.entity.DeliveryRecord.Status status,
                                                                  String error, String event, java.time.Instant at) {
        com.hospio.messaging.entity.DeliveryRecord r = com.hospio.messaging.entity.DeliveryRecord.builder().channel(channel).toAddress("x")
                .status(status).error(error).providerEvent(event).sentAt(at).build();
        r.setCreatedAt(at);
        return r;
    }

    @Test
    void eachRowShowsItsLastOutcomesAndFailingOnesTurnItToError() {
        InfobipClient infobip = new InfobipClient(server.url(), INFOBIP_KEY, "", "+2348148849797", "", "", "");
        TermiiClient termii = new TermiiClient(true, server.url(), TERMII_KEY, "OE Alert", "dnd");
        TextSender texts = new TextSender(infobip, termii, true, true, "en", "");
        ConnectionChecks checks = new ConnectionChecks(infobip, termii, texts, mail(BREVO_KEY), mailer, false, 3000);
        com.hospio.messaging.repository.DeliveryRecordRepository log = mock(com.hospio.messaging.repository.DeliveryRecordRepository.class);
        checks.setDeliveries(log);
        java.time.Instant now = java.time.Instant.now();
        var failed = com.hospio.messaging.entity.DeliveryRecord.Status.FAILED;
        var sent = com.hospio.messaging.entity.DeliveryRecord.Status.SENT;
        var delivered = com.hospio.messaging.entity.DeliveryRecord.Status.DELIVERED;
        String account = "Message sent not delivered - Account issue (EC_ACCOUNT_ISSUE)";
        when(log.latest(org.mockito.ArgumentMatchers.eq("WHATSAPP"), any(), any(), any())).thenReturn(List.of(
                row("WHATSAPP", failed, account, null, now.minusSeconds(7200)),
                // The staging allow-list's own refusal is not the provider's answer: skipped.
                row("WHATSAPP", failed, "Held back: not on this environment's allow-list (TEXTS_ALLOW_ONLY)", null, now.minusSeconds(7300)),
                row("WHATSAPP", failed, account, null, now.minusSeconds(7400)),
                row("WHATSAPP", failed, account, null, now.minusSeconds(7500))));
        when(log.latest(org.mockito.ArgumentMatchers.eq("EMAIL"), any(), any(), any())).thenReturn(List.of(
                row("EMAIL", delivered, null, "delivered", now.minusSeconds(300)),
                row("EMAIL", failed, "Bounced (soft): Unable to find MX of domain hospio-qa.test", "soft_bounce", now.minusSeconds(600)),
                row("EMAIL", sent, null, null, now.minusSeconds(900)),
                row("EMAIL", delivered, null, "delivered", now.minusSeconds(1200))));
        when(log.latest(org.mockito.ArgumentMatchers.eq("SMS"), any(), any(), any())).thenReturn(List.of());
        when(log.emailTrackingSince()).thenReturn(now.minusSeconds(3600));

        Map<String, ConnectionCheck> r = byKey(checks.run());

        assertThat(r.get("whatsapp").status()).isEqualTo("ERROR");
        assertThat(r.get("whatsapp").detail()).contains("Last message: failed 2 h ago — " + account,
                "Last 7 days: 3 sent, 0 delivered, 3 failed", "The last 3 all failed").doesNotContain("Held back");
        assertThat(r.get("email").status()).isEqualTo("CONNECTED");
        assertThat(r.get("email").detail()).contains("Last email: delivered 5 min ago. Tracking since ",
                "; last 7 days (real addresses): 4 sent, 2 delivered, 1 bounced (25%).", "the Brevo webhook key isn't set");
        assertThat(r.get("sms").detail()).contains("No SMS code sent in the last 7 days", "until its secret key is set");
    }

    // ---- email health, honestly (docs/integrations.md, "Email health") ----

    private static com.hospio.messaging.entity.DeliveryRecord mail(String to, com.hospio.messaging.entity.DeliveryRecord.Status status,
                                                                   String error, String event, java.time.Instant at) {
        com.hospio.messaging.entity.DeliveryRecord r = row("EMAIL", status, error, event, at);
        r.setToAddress(to);
        r.setProviderEventAt(event == null ? null : at.plusSeconds(5));
        return r;
    }

    private ConnectionChecks emailChecks(List<com.hospio.messaging.entity.DeliveryRecord> rows, java.time.Instant trackingSince) {
        InfobipClient infobip = new InfobipClient(server.url(), INFOBIP_KEY, "", "+2348148849797", "", "", "");
        TermiiClient termii = new TermiiClient(true, server.url(), TERMII_KEY, "OE Alert", "dnd");
        ConnectionChecks checks = new ConnectionChecks(infobip, termii, new TextSender(infobip, termii, true, true, "en", ""), mail(BREVO_KEY),
                mailer, false, 3000);
        com.hospio.messaging.repository.DeliveryRecordRepository log = mock(com.hospio.messaging.repository.DeliveryRecordRepository.class);
        when(log.latest(any(), any(), any(), any())).thenReturn(List.of());
        when(log.latest(org.mockito.ArgumentMatchers.eq("EMAIL"), any(), any(), any())).thenReturn(rows);
        when(log.emailTrackingSince()).thenReturn(trackingSince);
        checks.setDeliveries(log);
        return checks;
    }

    private static final com.hospio.messaging.entity.DeliveryRecord.Status SENT = com.hospio.messaging.entity.DeliveryRecord.Status.SENT;
    private static final com.hospio.messaging.entity.DeliveryRecord.Status DELIVERED = com.hospio.messaging.entity.DeliveryRecord.Status.DELIVERED;
    private static final com.hospio.messaging.entity.DeliveryRecord.Status FAILED = com.hospio.messaging.entity.DeliveryRecord.Status.FAILED;
    private static final String NO_MX = "Bounced (soft): Unable to find MX of domain hospio-qa.test";

    @Test
    void onlyTestAddressesSinceTrackingStartedIsANeutralNoteNotAnError() {
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant since = now.minusSeconds(4 * 3600);
        List<com.hospio.messaging.entity.DeliveryRecord> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            rows.add(mail("qa" + i + "@hospio-qa.test", FAILED, NO_MX, "soft_bounce", now.minusSeconds(600L * (i + 1))));
        }
        // Real addresses before the webhook: they can only ever say "sent".
        rows.add(mail("tunde@royalsuites.ng", SENT, null, null, now.minusSeconds(2 * 86400)));
        rows.add(mail("ops@hospio360.com", SENT, null, null, now.minusSeconds(3 * 86400)));

        ConnectionCheck email = byKey(emailChecks(rows, since).run()).get("email");

        assertThat(email.status()).isEqualTo("CONNECTED");
        assertThat(email.detail()).contains("Tracking since ", "No real emails since delivery tracking started.",
                        "Before then: last email: sent 2 d ago.", "6 emails to test addresses not counted.")
                .doesNotContain("bounced", "all failed", "hospio-qa.test");
    }

    @Test
    void theLastEmailIsTheNewestToARealAddressAndTestOnesAreNotCounted() {
        java.time.Instant now = java.time.Instant.now();
        List<com.hospio.messaging.entity.DeliveryRecord> rows = List.of(
                mail("qa@hospio-qa.test", FAILED, NO_MX, "soft_bounce", now.minusSeconds(60)),
                mail("owner@gmail.com", DELIVERED, null, "delivered", now.minusSeconds(600)),
                mail("x@example.com", FAILED, NO_MX, "hard_bounce", now.minusSeconds(700)));

        ConnectionCheck email = byKey(emailChecks(rows, now.minusSeconds(3600)).run()).get("email");

        assertThat(email.status()).isEqualTo("CONNECTED");
        assertThat(email.detail()).contains("Last email: delivered 9 min ago.", "last 7 days (real addresses): 1 sent, 1 delivered, 0 bounced (0%).",
                "2 emails to test addresses not counted.");
    }

    @Test
    void theLastThreeRealEmailsFailingIsAnError() {
        java.time.Instant now = java.time.Instant.now();
        List<com.hospio.messaging.entity.DeliveryRecord> rows = List.of(
                mail("a@gmail.com", FAILED, "Bounced (hard): 550 no such user", "hard_bounce", now.minusSeconds(60)),
                mail("b@gmail.com", FAILED, "Bounced (hard): 550 no such user", "hard_bounce", now.minusSeconds(120)),
                mail("c@yahoo.com", FAILED, "Brevo refused it: 401 unauthorised", null, now.minusSeconds(180)),
                mail("d@gmail.com", DELIVERED, null, "delivered", now.minusSeconds(240)));

        ConnectionCheck email = byKey(emailChecks(rows, now.minusSeconds(3600)).run()).get("email");

        assertThat(email.status()).isEqualTo("ERROR");
        assertThat(email.detail()).contains("Last email: failed", "Bounced (hard): 550 no such user", "4 sent, 1 delivered, 2 bounced (50%), 1 failed.",
                "The last 3 real emails all failed.");
    }

    @Test
    void aBounceRateOfTenPercentOverAtLeastTenRealEmailsIsAnError() {
        java.time.Instant now = java.time.Instant.now();
        List<com.hospio.messaging.entity.DeliveryRecord> rows = new java.util.ArrayList<>();
        rows.add(mail("gone@gmail.com", FAILED, "Bounced (hard): 550", "hard_bounce", now.minusSeconds(5000)));
        for (int i = 0; i < 9; i++) {
            rows.add(0, mail("guest" + i + "@gmail.com", DELIVERED, null, "delivered", now.minusSeconds(60L * (i + 1))));
        }
        assertThat(byKey(emailChecks(rows, now.minusSeconds(86400)).run()).get("email").status()).isEqualTo("ERROR");
        assertThat(byKey(emailChecks(rows, now.minusSeconds(86400)).run()).get("email").detail()).contains("10 sent, 9 delivered, 1 bounced (10%)",
                "The bounce rate is 10% or more.");

        // The same bounce among nine emails (too few to judge) is not an error.
        assertThat(byKey(emailChecks(rows.subList(1, 10).stream().toList(), now.minusSeconds(86400)).run()).get("email").status()).isEqualTo("CONNECTED");
    }

    @Test
    void beforeTrackingOnlySentAndFailedAreCounted() {
        java.time.Instant now = java.time.Instant.now();
        List<com.hospio.messaging.entity.DeliveryRecord> rows = List.of(
                mail("a@gmail.com", SENT, null, null, now.minusSeconds(600)),
                mail("qa@hospio-qa.test", FAILED, "Brevo refused it", null, now.minusSeconds(700)));

        ConnectionCheck email = byKey(emailChecks(rows, null).run()).get("email");

        assertThat(email.status()).isEqualTo("CONNECTED");
        assertThat(email.detail()).contains("Last email: sent 10 min ago. Last 7 days (real addresses): 1 sent, 0 failed.",
                "1 email to test addresses not counted.").doesNotContain("delivered,");
    }
}

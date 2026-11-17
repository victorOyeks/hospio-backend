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
        assertThat(r.get("email").detail()).contains("Brevo API, key present", "from no-reply@hospio360.com", "Brevo accepted the key");
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
}

package com.hospio.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hospio.common.integrations.settings.IntegrationSettings;
import com.hospio.messaging.billing.PaystackClient;
import com.hospio.messaging.controller.InfobipWebhookController;
import com.hospio.messaging.mail.JavaMailGateway;
import com.hospio.messaging.mail.MailDispatcher;
import com.hospio.messaging.mail.MailRouter;
import com.hospio.messaging.mail.SmtpGateway;
import com.hospio.messaging.text.InfobipClient;
import com.hospio.messaging.text.InfobipDtos;
import com.hospio.messaging.text.TermiiClient;
import com.hospio.messaging.text.TextSender;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Settings · Integrations in messaging-service: with nothing saved every client does what the environment says, as before; a value
 * saved in the portal wins over the environment on the next use, without a restart.
 */
class PortalIntegrationSettingsTest {

    private StubHttp server;
    private final StubIntegrations portal = new StubIntegrations();

    @BeforeEach
    void start() throws Exception {
        server = new StubHttp();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static String sign(String key, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void paystackUsesTheEnvironmentKeyUntilAKeyIsSavedThenThePortalOne() throws Exception {
        PaystackClient paystack = new PaystackClient("sk_test_env", server.url());
        paystack.setIntegrations(portal);
        String body = "{\"event\":\"charge.success\"}";
        assertThat(paystack.signed(body, sign("sk_test_env", body))).isTrue();

        portal.save(new IntegrationSettings.Paystack("sk_live_portal"));

        assertThat(paystack.signed(body, sign("sk_test_env", body))).isFalse();
        assertThat(paystack.signed(body, sign("sk_live_portal", body))).isTrue();
        server.on("/balance", 200, "{\"status\":true,\"message\":\"Balances retrieved\",\"data\":[{\"currency\":\"NGN\",\"balance\":1234550}]}");
        assertThat(paystack.check().ok()).isTrue();
        assertThat(paystack.check().message()).isEqualTo("Paystack accepted the key (live mode): balance NGN 12345.50");
    }

    @Test
    void paystackWithNoKeyAnywhereIsNotSetUp() {
        PaystackClient paystack = new PaystackClient("", server.url());
        paystack.setIntegrations(portal);
        assertThat(paystack.configured()).isFalse();
        assertThat(paystack.check().message()).isEqualTo("There is no Paystack secret key, saved here or on the server");
    }

    @Test
    void termiiSendsWithThePortalKeyAndSenderAndCanBeSwitchedOnFromThePortal() {
        TermiiClient termii = new TermiiClient(false, server.url(), "env-key", "Hospio360", "dnd");
        termii.setIntegrations(portal);
        assertThat(termii.configured()).as("TERMII_ENABLED is off on the server").isFalse();

        portal.save(new IntegrationSettings.Termii(true, null, "portal-key", "HospioNG", null));
        server.on("/api/sms/send", 200, "{\"code\":\"ok\",\"message_id\":\"m1\",\"message\":\"Successfully Sent\"}");

        assertThat(termii.sms("2349035911994", "Your code is 482913").ok()).isTrue();
        assertThat(server.last().body()).contains("\"api_key\":\"portal-key\"").contains("\"from\":\"HospioNG\"").contains("\"channel\":\"dnd\"");
    }

    @Test
    void infobipSendsFromThePortalSenderWithTheReportAddressAndKeyFromThePortal() {
        InfobipClient infobip = new InfobipClient(server.url(), "env-key", "", "+2348000000000", "", "", "https://hospio360.com/banner.png");
        infobip.setIntegrations(portal);
        server.on("/whatsapp/1/message/template", 200, "{\"messages\":[{\"messageId\":\"w1\",\"status\":{\"groupName\":\"PENDING\"}}]}");
        infobip.whatsAppTemplate("2349035911994", "hospio_booking_changed", "en", List.of("Ada"), false);
        assertThat(server.last().body()).contains("\"from\":\"2348000000000\"").doesNotContain("notifyUrl");

        portal.save(new IntegrationSettings.Infobip(null, null, "+2348148849797", null, "https://api.hospio360.com/messaging/webhooks/infobip/reports",
                "wh-key", null, null));
        infobip.whatsAppTemplate("2349035911994", "hospio_booking_changed", "en", List.of("Ada"), false);

        assertThat(server.last().body()).contains("\"from\":\"2348148849797\"")
                .contains("\"notifyUrl\":\"https://api.hospio360.com/messaging/webhooks/infobip/reports?key=wh-key\"");
    }

    @Test
    void theInfobipWebhookBelievesThePortalKeyOnceOneIsSaved() {
        MailDispatcher dispatcher = mock(MailDispatcher.class);
        InfobipWebhookController controller = new InfobipWebhookController(dispatcher);
        ReflectionTestUtils.setField(controller, "webhookKey", "env-key");
        controller.setIntegrations(portal);
        InfobipDtos.Reports none = new InfobipDtos.Reports(List.of());
        assertThat(controller.reports("env-key", none).getStatusCode().value()).isEqualTo(200);

        portal.save(new IntegrationSettings.Infobip(null, null, null, null, null, "portal-key", null, null));

        assertThat(controller.reports("env-key", none).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.reports("portal-key", none).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void smsSwitchedOffInThePortalWinsOverSmsEnabledOnTheServer() {
        InfobipClient infobip = mock(InfobipClient.class);
        TermiiClient termii = mock(TermiiClient.class);
        when(infobip.configured()).thenReturn(true);
        when(infobip.sms(anyString(), anyString())).thenReturn(new InfobipClient.Sent(true, "s1", null));
        TextSender texts = new TextSender(infobip, termii, true, false, "en", "");
        texts.setIntegrations(portal);
        assertThat(texts.smsLive()).isTrue();

        portal.save(new IntegrationSettings.Infobip(null, null, null, null, null, null, false, null));

        assertThat(texts.smsLive()).isFalse();
        assertThat(texts.send("SMS", "08031234567", "Code 1", null, List.of(), true).note()).isEqualTo("SMS is off");
    }

    @Test
    @SuppressWarnings("unchecked")
    void emailGoesBySmtpUntilABrevoKeyIsSavedThenThroughBrevo() {
        JavaMailSenderImpl spring = new JavaMailSenderImpl();
        spring.setHost("smtp.env.example");
        ObjectProvider<JavaMailSenderImpl> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(spring);
        JavaMailGateway smtp = new JavaMailGateway(provider);
        smtp.setIntegrations(portal);
        ObjectProvider<com.hospio.common.integrations.settings.IntegrationSettingsReader> readers = mock(ObjectProvider.class);
        when(readers.getIfAvailable()).thenReturn(portal);
        MailRouter router = new MailRouter(smtp, "", server.url() + "/v3", readers);
        assertThat(router.brevo().configured()).isFalse();
        assertThat(router.configured()).isTrue();

        portal.save(new IntegrationSettings.Brevo("xkeysib-portal-123456", null, null));
        server.on("/v3/smtp/email", 201, "{\"messageId\":\"<1@smtp-relay.mailin.fr>\"}");

        SmtpGateway.Accepted a = router.send(new SmtpGateway.Email("no-reply@hospio360.com", "Hospio360", "ada@example.com", null, "Hi", "<p>Hi</p>", "Hi"));
        assertThat(a.reply()).isEqualTo("Brevo API 201");
    }

    @Test
    void smtpSettingsSavedInThePortalBuildTheirOwnSenderOverSpringsOne() {
        JavaMailSenderImpl spring = new JavaMailSenderImpl();
        spring.setHost("smtp.env.example");
        spring.setPort(587);
        spring.setUsername("env-user");
        spring.getJavaMailProperties().put("mail.smtp.starttls.enable", "true");
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSenderImpl> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(spring);
        JavaMailGateway smtp = new JavaMailGateway(provider);
        smtp.setIntegrations(portal);
        assertThat(ReflectionTestUtils.invokeMethod(smtp, "current", new Object[0]) == spring).as("nothing saved: Spring's sender as is").isTrue();

        portal.save(new IntegrationSettings.Smtp("smtp-relay.brevo.com", 2525, null, "portal-pass", true));
        JavaMailSenderImpl built = ReflectionTestUtils.invokeMethod(smtp, "current", new Object[0]);

        assertThat(built.getHost()).isEqualTo("smtp-relay.brevo.com");
        assertThat(built.getPort()).isEqualTo(2525);
        assertThat(built.getUsername()).as("not saved: the server's").isEqualTo("env-user");
        assertThat(built.getPassword()).isEqualTo("portal-pass");
        assertThat(built.getJavaMailProperties()).containsEntry("mail.smtp.starttls.enable", "true");
        JavaMailSenderImpl again = ReflectionTestUtils.invokeMethod(smtp, "current", new Object[0]);
        assertThat(again == built).as("rebuilt only when the settings change").isTrue();
    }
}

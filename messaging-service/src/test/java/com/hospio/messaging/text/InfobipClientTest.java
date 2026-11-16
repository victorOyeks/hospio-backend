package com.hospio.messaging.text;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.messaging.StubHttp;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * docs/sms-whatsapp.md: a template is sent in its own shape (banner header, link button) and read back with that shape; the
 * JSON sent to Infobip is exactly its documented shape, with optional parts left out.
 */
class InfobipClientTest {

    private static final String ACCEPTED = """
            {"bulkId":"b-1","messages":[{"messageId":"m1","to":"2349035911994",
             "status":{"groupId":1,"groupName":"PENDING","id":7,"name":"PENDING_ENROUTE","description":"Message sent to next instance"}}]}""";

    private final ObjectMapper json = new ObjectMapper();
    private StubHttp server;

    @BeforeEach
    void start() throws Exception {
        server = new StubHttp().on("/whatsapp/1/message/template", 200, ACCEPTED).on("/sms/2/text/advanced", 200, ACCEPTED)
                .on("/whatsapp/2/senders/2348148849797/templates", 200, "{\"templates\":[]}");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private InfobipClient client() {
        return client("", "", "");
    }

    private InfobipClient client(String smsSender, String notifyUrl, String webhookKey) {
        return new InfobipClient(server.url(), "k", smsSender, "+2348148849797", notifyUrl, webhookKey,
                "https://hospio360.com/whatsapp/hospio360-banner.png");
    }

    private InfobipDtos.TemplateData sentData() throws Exception {
        return json.readValue(server.last().body(), InfobipDtos.TemplateRequest.class).messages().get(0).content().templateData();
    }

    /** The sent body with its random messageId fixed, for comparing the whole JSON. */
    private String sentJson() {
        return server.last().body().replaceAll("\"messageId\":\"[0-9a-f-]{36}\"", "\"messageId\":\"ID\"");
    }

    @Test
    void aBannerAndLinkTemplateGetsTheImageAndTheLastValueOnItsButton() throws Exception {
        InfobipClient.Sent s = client().whatsAppTemplate("2349035911994", "hospio_booking_confirmation_v2", "en",
                List.of("Victor", "Simpas Ikeja", "HBT7UFWH", "TOKEN123"), new InfobipClient.TemplateInfo("APPROVED", "UTILITY", "en", true, true));
        assertThat(s.ok()).isTrue();
        assertThat(s.messageId()).isEqualTo("m1");
        InfobipDtos.TemplateData d = sentData();
        assertThat(d.header().type()).isEqualTo("IMAGE");
        assertThat(d.header().mediaUrl()).isEqualTo("https://hospio360.com/whatsapp/hospio360-banner.png");
        assertThat(d.body().placeholders()).containsExactly("Victor", "Simpas Ikeja", "HBT7UFWH");
        assertThat(d.buttons()).containsExactly(new InfobipDtos.Button("URL", "TOKEN123"));
        assertThat(sentJson()).isEqualTo("{\"messages\":[{\"from\":\"2348148849797\",\"to\":\"2349035911994\",\"messageId\":\"ID\","
                + "\"content\":{\"templateName\":\"hospio_booking_confirmation_v2\",\"templateData\":{"
                + "\"body\":{\"placeholders\":[\"Victor\",\"Simpas Ikeja\",\"HBT7UFWH\"]},"
                + "\"header\":{\"type\":\"IMAGE\",\"mediaUrl\":\"https://hospio360.com/whatsapp/hospio360-banner.png\"},"
                + "\"buttons\":[{\"type\":\"URL\",\"parameter\":\"TOKEN123\"}]},\"language\":\"en\"}}]}");
    }

    @Test
    void aHotelsOwnBannerReplacesTheHospio360One() throws Exception {
        client().whatsAppTemplate("2349035911994", "hospio_booking_confirmation_v2", "en", List.of("Victor", "TOKEN"),
                new InfobipClient.TemplateInfo("APPROVED", "UTILITY", "en", true, true), "https://hospio360.com/whatsapp/banner?c=0b4f50&n=Simpas");
        assertThat(sentData().header().mediaUrl()).isEqualTo("https://hospio360.com/whatsapp/banner?c=0b4f50&n=Simpas");
    }

    @Test
    void aPlainTemplateSendsOnlyItsBody() throws Exception {
        client().whatsAppTemplate("2349035911994", "hospio_booking_changed", "en", List.of("Ada", "BK-1"), false);
        InfobipDtos.TemplateData d = sentData();
        assertThat(d.header()).isNull();
        assertThat(d.buttons()).isNull();
        assertThat(d.body().placeholders()).hasSize(2);
        assertThat(sentJson()).isEqualTo("{\"messages\":[{\"from\":\"2348148849797\",\"to\":\"2349035911994\",\"messageId\":\"ID\","
                + "\"content\":{\"templateName\":\"hospio_booking_changed\",\"templateData\":{\"body\":{\"placeholders\":[\"Ada\",\"BK-1\"]}},"
                + "\"language\":\"en\"}}]}");
    }

    @Test
    void anAuthenticationTemplateCarriesTheCodeInItsBodyAndOnItsButton() {
        client("", "https://api.hospio360.com/messaging/webhooks/infobip/reports", "wk").whatsAppTemplate("2349035911994", "hospio_code", "en",
                List.of("482913"), true);
        assertThat(sentJson()).isEqualTo("{\"messages\":[{\"from\":\"2348148849797\",\"to\":\"2349035911994\",\"messageId\":\"ID\","
                + "\"content\":{\"templateName\":\"hospio_code\",\"templateData\":{\"body\":{\"placeholders\":[\"482913\"]},"
                + "\"buttons\":[{\"type\":\"URL\",\"parameter\":\"482913\"}]},\"language\":\"en\"},"
                + "\"notifyUrl\":\"https://api.hospio360.com/messaging/webhooks/infobip/reports?key=wk\"}]}");
    }

    @Test
    void anSmsIsSentWithOnlyTheFieldsThatAreSet() {
        InfobipClient.Sent s = client().sms("2349035911994", "Your code is 482913");
        assertThat(s.ok()).isTrue();
        assertThat(server.last().body()).isEqualTo("{\"messages\":[{\"destinations\":[{\"to\":\"2349035911994\"}],\"text\":\"Your code is 482913\"}]}");

        client("Hospio360", "https://api.hospio360.com/messaging/webhooks/infobip/reports", "wk").sms("2349035911994", "Hi");
        assertThat(server.last().body()).isEqualTo("{\"messages\":[{\"destinations\":[{\"to\":\"2349035911994\"}],\"text\":\"Hi\","
                + "\"from\":\"Hospio360\",\"notifyUrl\":\"https://api.hospio360.com/messaging/webhooks/infobip/reports?key=wk\","
                + "\"notifyContentType\":\"application/json\"}]}");
    }

    @Test
    void aRejectedMessageIsAFailureWithInfobipsWords() {
        server.on("/sms/2/text/advanced", 200, """
                {"messages":[{"messageId":"m2","to":"2349035911994",
                 "status":{"groupId":5,"groupName":"REJECTED","id":6,"name":"REJECTED_NETWORK","description":"Network is forbidden"}}]}""");
        InfobipClient.Sent s = client().sms("2349035911994", "Hi");
        assertThat(s.ok()).isFalse();
        assertThat(s.error()).isEqualTo("REJECTED_NETWORK: Network is forbidden");
    }

    @Test
    void anHttpRefusalCarriesTheServiceExceptionText() {
        server.on("/sms/2/text/advanced", 401, """
                {"requestError":{"serviceException":{"messageId":"UNAUTHORIZED","text":"Invalid login details"}}}""");
        InfobipClient.Sent s = client().sms("2349035911994", "Hi");
        assertThat(s.ok()).isFalse();
        assertThat(s.error()).isEqualTo("HTTP 401: Invalid login details");

        server.on("/sms/2/text/advanced", 500, "");
        assertThat(client().sms("2349035911994", "Hi").error()).isEqualTo("HTTP 500");
    }

    @Test
    void theShapeIsReadFromInfobip() {
        server.on("/whatsapp/2/senders/2348148849797/templates", 200, """
                {"templates":[
                 {"id":"1","businessAccountId":2,"name":"v2","status":"APPROVED","category":"UTILITY","language":"en","structure":{"header":{"format":"IMAGE"},
                  "body":{"text":"Hi {{1}}"},"buttons":[{"type":"URL","text":"View booking","url":"https://hospio360.com/stay/{{1}}"}]},"quality":"UNKNOWN"},
                 {"name":"v1","status":"APPROVED","category":"UTILITY","language":"en","structure":{"header":{"format":"TEXT","text":"Hi"},
                  "buttons":[{"type":"URL","text":"Site","url":"https://hospio360.com"}]}},
                 {"name":"pending","status":"PENDING","category":"MARKETING","language":"en_GB","structure":{"body":{"text":"x"}}}]}
                """);
        var t = client().templates();
        assertThat(t.get("v2").imageHeader()).isTrue();
        assertThat(t.get("v2").linkButton()).isTrue();
        assertThat(t.get("v2").approved()).isTrue();
        assertThat(t.get("v1").imageHeader()).isFalse();
        assertThat(t.get("v1").linkButton()).isFalse();
        assertThat(t.get("pending")).isEqualTo(new InfobipClient.TemplateInfo("PENDING", "MARKETING", "en_GB", false, false));
    }
}

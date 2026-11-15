package com.hospio.messaging.text;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** docs/sms-whatsapp.md: a template is sent in its own shape (banner header, link button) and read back with that shape. */
class InfobipClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private String templatesJson = "{\"templates\":[]}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/whatsapp/1/message/template", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"messages\":[{\"messageId\":\"m1\",\"status\":{\"groupName\":\"PENDING\"}}]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/whatsapp/2/senders/2348148849797/templates", ex -> {
            byte[] out = templatesJson.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private InfobipClient client() {
        return new InfobipClient("http://127.0.0.1:" + server.getAddress().getPort(), "k", "", "2348148849797", "", "",
                "https://hospio360.com/whatsapp/hospio360-banner.png");
    }

    private JsonNode sentData() throws Exception {
        return new ObjectMapper().readTree(lastBody.get()).path("messages").path(0).path("content").path("templateData");
    }

    @Test
    void aBannerAndLinkTemplateGetsTheImageAndTheLastValueOnItsButton() throws Exception {
        InfobipClient.Sent s = client().whatsAppTemplate("2349035911994", "hospio_booking_confirmation_v2", "en",
                List.of("Victor", "Simpas Ikeja", "HBT7UFWH", "TOKEN123"), new InfobipClient.TemplateInfo("APPROVED", "UTILITY", "en", true, true));
        assertThat(s.ok()).isTrue();
        JsonNode d = sentData();
        assertThat(d.path("header").path("type").asText()).isEqualTo("IMAGE");
        assertThat(d.path("header").path("mediaUrl").asText()).isEqualTo("https://hospio360.com/whatsapp/hospio360-banner.png");
        assertThat(d.path("body").path("placeholders")).hasSize(3);
        assertThat(d.path("body").path("placeholders").path(2).asText()).isEqualTo("HBT7UFWH");
        assertThat(d.path("buttons").path(0).path("type").asText()).isEqualTo("URL");
        assertThat(d.path("buttons").path(0).path("parameter").asText()).isEqualTo("TOKEN123");
    }

    @Test
    void aHotelsOwnBannerReplacesTheHospio360One() throws Exception {
        client().whatsAppTemplate("2349035911994", "hospio_booking_confirmation_v2", "en", List.of("Victor", "TOKEN"),
                new InfobipClient.TemplateInfo("APPROVED", "UTILITY", "en", true, true), "https://hospio360.com/whatsapp/banner?c=0b4f50&n=Simpas");
        assertThat(sentData().path("header").path("mediaUrl").asText()).isEqualTo("https://hospio360.com/whatsapp/banner?c=0b4f50&n=Simpas");
    }

    @Test
    void aPlainTemplateSendsOnlyItsBody() throws Exception {
        client().whatsAppTemplate("2349035911994", "hospio_booking_changed", "en", List.of("Ada", "BK-1"), false);
        JsonNode d = sentData();
        assertThat(d.has("header")).isFalse();
        assertThat(d.has("buttons")).isFalse();
        assertThat(d.path("body").path("placeholders")).hasSize(2);
    }

    @Test
    void theShapeIsReadFromInfobip() {
        templatesJson = """
                {"templates":[
                 {"name":"v2","status":"APPROVED","category":"UTILITY","language":"en","structure":{"header":{"format":"IMAGE"},
                  "buttons":[{"type":"URL","text":"View booking","url":"https://hospio360.com/stay/{{1}}"}]}},
                 {"name":"v1","status":"APPROVED","category":"UTILITY","language":"en","structure":{"header":{"format":"TEXT","text":"Hi"}}}]}
                """;
        var t = client().templates();
        assertThat(t.get("v2").imageHeader()).isTrue();
        assertThat(t.get("v2").linkButton()).isTrue();
        assertThat(t.get("v1").imageHeader()).isFalse();
        assertThat(t.get("v1").linkButton()).isFalse();
    }
}

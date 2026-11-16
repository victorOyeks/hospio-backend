package com.hospio.messaging.text;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.messaging.StubHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** docs/sms-whatsapp.md: a code goes to Termii's /api/sms/send on the dnd route in exactly Termii's JSON. */
class TermiiClientTest {

    private StubHttp server;

    @BeforeEach
    void start() throws Exception {
        server = new StubHttp();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private TermiiClient client() {
        return new TermiiClient(true, server.url() + "/", " key-1 ", "Hospio360", "dnd");
    }

    @Test
    void theRequestIsTermiisSendShapeAndItsIdIsKept() {
        server.on("/api/sms/send", 200, """
                {"code":"ok","message_id":"3017544054459083819856413","message_id_str":"3017544054459083819856413",
                 "message":"Successfully Sent","balance":120.5,"user":"Hospio360"}""");
        InfobipClient.Sent s = client().sms("2349035911994", "Your Hospio360 code is 482913");
        assertThat(server.last().method()).isEqualTo("POST");
        assertThat(server.last().body()).isEqualTo("{\"api_key\":\"key-1\",\"to\":\"2349035911994\",\"from\":\"Hospio360\","
                + "\"sms\":\"Your Hospio360 code is 482913\",\"type\":\"plain\",\"channel\":\"dnd\"}");
        assertThat(s).isEqualTo(new InfobipClient.Sent(true, "3017544054459083819856413", null));
    }

    @Test
    void aNumericIdIsReadAsText() {
        server.on("/api/sms/send", 200, "{\"code\":\"ok\",\"message_id\":9122821270554876574,\"message\":\"Successfully Sent\"}");
        assertThat(client().sms("2349035911994", "x").messageId()).isEqualTo("9122821270554876574");
    }

    @Test
    void aRefusalCarriesTermiisMessage() {
        server.on("/api/sms/send", 400, "{\"message\":\"ApplicationSenderId not found\"}");
        assertThat(client().sms("2349035911994", "x")).isEqualTo(new InfobipClient.Sent(false, null, "Termii HTTP 400: ApplicationSenderId not found"));
        server.on("/api/sms/send", 200, "{\"code\":\"ok\"}");
        assertThat(client().sms("2349035911994", "x").error()).isEqualTo("Termii HTTP 200");
    }

    @Test
    void offMeansNothingIsSent() {
        assertThat(new TermiiClient(false, server.url(), "k", "Hospio360", "dnd").sms("234", "x").error()).isEqualTo("Termii not configured");
        assertThat(server.seen()).isEmpty();
    }
}

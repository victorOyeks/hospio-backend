package com.hospio.messaging.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.messaging.StubHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** docs/email.md: Brevo's POST /v3/smtp/email gets its documented JSON, optional parts left out; its messageId is kept. */
class BrevoApiGatewayTest {

    private StubHttp server;
    private BrevoApiGateway gateway;

    @BeforeEach
    void start() throws Exception {
        server = new StubHttp().on("/v3/smtp/email", 201, "{\"messageId\":\"<202611100912.12345678901@smtp-relay.mailin.fr>\"}");
        gateway = new BrevoApiGateway("xkeysib-123", server.url() + "/v3/");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void aFullEmailHasEveryPart() {
        SmtpGateway.Accepted a = gateway.send(new SmtpGateway.Email("noreply@hospio360.com", "Simpas Ikeja", "ada@example.com",
                "frontdesk@simpas.ng", "Your booking HBT7UFWH", "<p>Hi</p>", "Hi"));
        assertThat(server.last().body()).isEqualTo("{\"sender\":{\"email\":\"noreply@hospio360.com\",\"name\":\"Simpas Ikeja\"},"
                + "\"to\":[{\"email\":\"ada@example.com\"}],\"replyTo\":{\"email\":\"frontdesk@simpas.ng\"},\"subject\":\"Your booking HBT7UFWH\","
                + "\"htmlContent\":\"<p>Hi</p>\",\"textContent\":\"Hi\"}");
        assertThat(a).isEqualTo(new SmtpGateway.Accepted("<202611100912.12345678901@smtp-relay.mailin.fr>", "Brevo API 201"));
    }

    @Test
    void unsetPartsAreLeftOut() {
        gateway.send(new SmtpGateway.Email("noreply@hospio360.com", " ", "ada@example.com", null, "Code", null, "482913"));
        assertThat(server.last().body()).isEqualTo("{\"sender\":{\"email\":\"noreply@hospio360.com\"},\"to\":[{\"email\":\"ada@example.com\"}],"
                + "\"subject\":\"Code\",\"textContent\":\"482913\"}");
    }

    @Test
    void aRefusalSaysWhyAndOnlyServerErrorsAreWorthRetrying() {
        server.on("/v3/smtp/email", 400, "{\"code\":\"invalid_parameter\",\"message\":\"sender is not valid\"}");
        assertThatThrownBy(() -> gateway.send(new SmtpGateway.Email("x@y.z", null, "a@b.c", null, "s", "h", null)))
                .isInstanceOfSatisfying(SmtpGateway.SendFailure.class, f -> {
                    assertThat(f.getMessage()).isEqualTo("Brevo 400: sender is not valid");
                    assertThat(f.isTransient()).isFalse();
                });
        server.on("/v3/smtp/email", 503, "{\"code\":\"unavailable\"}");
        assertThatThrownBy(() -> gateway.send(new SmtpGateway.Email("x@y.z", null, "a@b.c", null, "s", "h", null)))
                .isInstanceOfSatisfying(SmtpGateway.SendFailure.class, f -> {
                    assertThat(f.getMessage()).isEqualTo("Brevo 503: unavailable");
                    assertThat(f.isTransient()).isTrue();
                });
    }
}

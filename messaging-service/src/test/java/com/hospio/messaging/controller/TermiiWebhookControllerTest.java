package com.hospio.messaging.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import com.hospio.messaging.mail.MailDispatcher;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** docs/sms-whatsapp.md: Termii's delivery reports, believed only with a valid X-Termii-Signature. */
class TermiiWebhookControllerTest {

    private final MailDispatcher dispatcher = mock(MailDispatcher.class);
    private final TermiiWebhookController controller = new TermiiWebhookController(dispatcher);
    private static final String BODY = "{\"type\":\"outbound\",\"message_id\":\"3017544054459083819856413\",\"receiver\":\"2349035911994\","
            + "\"sender\":\"Hospio360\",\"status\":\"DELIVERED\",\"channel\":\"dnd\"}";

    private static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aSignedReportMarksTheCodeDelivered() throws Exception {
        ReflectionTestUtils.setField(controller, "secretKey", "s3cret");
        when(dispatcher.applyReport("termii:3017544054459083819856413", "DELIVERED", "DELIVERED")).thenReturn(true);
        assertThat(controller.report(sign(BODY, "s3cret"), BODY).getStatusCode().value()).isEqualTo(200);
        verify(dispatcher).applyReport("termii:3017544054459083819856413", "DELIVERED", "DELIVERED");
    }

    @Test
    void anUnsignedOrForgedReportIsRefused() throws Exception {
        ReflectionTestUtils.setField(controller, "secretKey", "s3cret");
        assertThat(controller.report(null, BODY).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.report(sign(BODY, "other"), BODY).getStatusCode().value()).isEqualTo(401);
        ReflectionTestUtils.setField(controller, "secretKey", "");
        assertThat(controller.report(sign(BODY, "s3cret"), BODY).getStatusCode().value()).isEqualTo(401);
        verify(dispatcher, never()).applyReport(anyString(), anyString(), anyString());
    }

    @Test
    void theSignatureIsCheckedOnTheRawBodyAsSent() throws Exception {
        ReflectionTestUtils.setField(controller, "secretKey", "s3cret");
        // Spacing, field order and a numeric id exactly as posted: re-serializing would change the bytes and the signature.
        String raw = "{ \"status\" : \"DND Active on Phone Number\", \"id\": 7731, \"receiver\":\"2349035911994\",\n"
                + "  \"message\":\"Your code is 482913\", \"sent_at\":\"2026-11-10 09:12:01\", \"cost\": 4.0 }";
        when(dispatcher.applyReport("termii:7731", "REJECTED", "DND Active on Phone Number")).thenReturn(true);
        assertThat(controller.report(sign(raw, "s3cret"), raw).getStatusCode().value()).isEqualTo(200);
        verify(dispatcher).applyReport("termii:7731", "REJECTED", "DND Active on Phone Number");

        String reformatted = raw.replace(" ", "");
        assertThat(controller.report(sign(raw, "s3cret"), reformatted).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void theRecordedIdIsFoundWhicheverFieldTheReportNamesItIn() throws Exception {
        ReflectionTestUtils.setField(controller, "secretKey", "s3cret");
        // The send answer's message_id_str was recorded; this report carries its own id first and the message id in message_id_str.
        String raw = "{\"type\":\"outbound\",\"id\":\"rep-1\",\"message_id_str\":\"3017544054459083819856413\",\"status\":\"Delivered\"}";
        when(dispatcher.applyReport("termii:3017544054459083819856413", "DELIVERED", "Delivered")).thenReturn(true);
        assertThat(controller.report(sign(raw, "s3cret"), raw).getStatusCode().value()).isEqualTo(200);
        verify(dispatcher).applyReport("termii:3017544054459083819856413", "DELIVERED", "Delivered");
    }

    @Test
    void termiisWordsBecomeTheStatusGroups() {
        assertThat(TermiiWebhookController.group("DELIVERED")).isEqualTo("DELIVERED");
        assertThat(TermiiWebhookController.group("Delivered")).isEqualTo("DELIVERED");
        assertThat(TermiiWebhookController.group("DND Active on Phone Number")).isEqualTo("REJECTED");
        assertThat(TermiiWebhookController.group("Rejected")).isEqualTo("REJECTED");
        assertThat(TermiiWebhookController.group("Message Failed")).isEqualTo("UNDELIVERABLE");
        assertThat(TermiiWebhookController.group("Expired")).isEqualTo("EXPIRED");
        assertThat(TermiiWebhookController.group("Message Sent")).isNull();
    }
}

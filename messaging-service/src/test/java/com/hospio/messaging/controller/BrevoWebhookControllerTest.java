package com.hospio.messaging.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.messaging.mail.MailDispatcher;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** docs/email.md, "Delivery tracking": Brevo's email events, believed only with the webhook key as a Bearer token or Basic password. */
class BrevoWebhookControllerTest {

    private static final String KEY = "brevo-hook-6f2a91c4d7";
    private final MailDispatcher dispatcher = mock(MailDispatcher.class);
    private final BrevoWebhookController controller = new BrevoWebhookController(dispatcher);

    private static final String BOUNCE = "{\"event\":\"soft_bounce\",\"email\":\"qa.msg@hospio-qa.test\",\"id\":123,"
            + "\"message-id\":\"<202611181200.1234567890@smtp-relay.mailin.fr>\",\"reason\":\"Unable to find MX of domain hospio-qa.test\","
            + "\"ts_event\":1795003200}";

    @Test
    void aBearerKeyedEventIsApplied() {
        ReflectionTestUtils.setField(controller, "webhookKey", KEY);
        when(dispatcher.applyEmailEvent(any(), any(), any(), any())).thenReturn(true);
        assertThat(controller.events("Bearer " + KEY, BOUNCE).getStatusCode().value()).isEqualTo(200);
        verify(dispatcher).applyEmailEvent("<202611181200.1234567890@smtp-relay.mailin.fr>", "soft_bounce",
                "Unable to find MX of domain hospio-qa.test", Instant.ofEpochSecond(1795003200));
    }

    @Test
    void basicAuthWithTheKeyAsPasswordIsBelievedToo() {
        ReflectionTestUtils.setField(controller, "webhookKey", KEY);
        String basic = "Basic " + Base64.getEncoder().encodeToString(("hospio:" + KEY).getBytes(StandardCharsets.UTF_8));
        assertThat(controller.events(basic, BOUNCE).getStatusCode().value()).isEqualTo(200);
        verify(dispatcher).applyEmailEvent(eq("<202611181200.1234567890@smtp-relay.mailin.fr>"), eq("soft_bounce"), anyString(), any());
    }

    @Test
    void aMissingWrongOrUnsetKeyIsRefusedAndNothingChanges() {
        ReflectionTestUtils.setField(controller, "webhookKey", KEY);
        assertThat(controller.events(null, BOUNCE).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.events("Bearer nope", BOUNCE).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.events(KEY, BOUNCE).getStatusCode().value()).isEqualTo(401);
        ReflectionTestUtils.setField(controller, "webhookKey", "");
        assertThat(controller.events("Bearer " + KEY, BOUNCE).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.events("Bearer ", BOUNCE).getStatusCode().value()).isEqualTo(401);
        verify(dispatcher, never()).applyEmailEvent(any(), any(), any(), any());
    }

    @Test
    void aBatchIsAppliedEventByEventAndJunkIsAcceptedQuietly() {
        ReflectionTestUtils.setField(controller, "webhookKey", KEY);
        String batch = "[{\"event\":\"delivered\",\"message-id\":\"<a@relay>\",\"ts_epoch\":1795003200123},"
                + "{\"event\":\"hard_bounce\",\"message-id\":\"<b@relay>\",\"reason\":\"550 no such user\"}]";
        assertThat(controller.events("Bearer " + KEY, batch).getStatusCode().value()).isEqualTo(200);
        verify(dispatcher).applyEmailEvent("<a@relay>", "delivered", null, Instant.ofEpochMilli(1795003200123L));
        verify(dispatcher).applyEmailEvent(eq("<b@relay>"), eq("hard_bounce"), eq("550 no such user"), any());
        // Not JSON: 200 so Brevo doesn't retry, nothing applied.
        assertThat(controller.events("Bearer " + KEY, "not json").getStatusCode().value()).isEqualTo(200);
    }
}

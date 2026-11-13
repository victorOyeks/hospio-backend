package com.hospio.messaging.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.messaging.mail.MailDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** docs/sms-whatsapp.md: Infobip's delivery reports, believed only with the key, reasons kept in words. */
class InfobipWebhookControllerTest {

    private final MailDispatcher dispatcher = mock(MailDispatcher.class);
    private final InfobipWebhookController controller = new InfobipWebhookController(dispatcher);

    private JsonNode dndReport() throws Exception {
        return new ObjectMapper().readTree("""
                {"results":[{"messageId":"179458016294507950965230","to":"2349035911994",
                 "status":{"groupName":"REJECTED","name":"REJECTED_DND","description":"Destination on DND list"},
                 "error":{"name":"EC_DEST_ADDRESS_BLACKLISTED","description":"DND blacklisted"}}]}""");
    }

    @Test
    void aRejectedSmsIsRecordedWithTheNetworksReason() throws Exception {
        ReflectionTestUtils.setField(controller, "webhookKey", "k-123");
        when(dispatcher.applyReport(eq("sms:179458016294507950965230"), eq("REJECTED"), anyString())).thenReturn(true);
        assertThat(controller.reports("k-123", dndReport()).getStatusCode().value()).isEqualTo(200);
        verify(dispatcher).applyReport("sms:179458016294507950965230", "REJECTED",
                "Destination on DND list - DND blacklisted (REJECTED_DND, EC_DEST_ADDRESS_BLACKLISTED)");
    }

    @Test
    void postsWithoutTheKeyAreRefused() throws Exception {
        ReflectionTestUtils.setField(controller, "webhookKey", "k-123");
        assertThat(controller.reports("wrong", dndReport()).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.reports(null, dndReport()).getStatusCode().value()).isEqualTo(401);
        verify(dispatcher, never()).applyReport(anyString(), anyString(), anyString());
    }
}

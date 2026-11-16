package com.hospio.messaging.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.messaging.mail.MailDispatcher;
import com.hospio.messaging.text.InfobipDtos;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

/** docs/sms-whatsapp.md: Infobip's delivery reports, believed only with the key, reasons kept in words. */
class InfobipWebhookControllerTest {

    private final MailDispatcher dispatcher = mock(MailDispatcher.class);
    private final InfobipWebhookController controller = new InfobipWebhookController(dispatcher);

    private static InfobipDtos.Reports read(String body) throws Exception {
        return new ObjectMapper().readValue(body, InfobipDtos.Reports.class);
    }

    /** An SMS delivery report as Infobip posts it (with the fields we don't read). */
    private InfobipDtos.Reports dndReport() throws Exception {
        return read("""
                {"results":[{"bulkId":"b-9","price":{"pricePerMessage":0,"currency":"EUR"},"messageId":"179458016294507950965230",
                 "to":"2349035911994","sentAt":"2026-11-10T09:12:01.000+0000","doneAt":"2026-11-10T09:12:03.000+0000","smsCount":1,
                 "mccMnc":"62130","callbackData":null,
                 "status":{"groupId":5,"groupName":"REJECTED","id":87,"name":"REJECTED_DND","description":"Destination on DND list"},
                 "error":{"groupId":3,"groupName":"OPERATOR_ERRORS","id":4110,"name":"EC_DEST_ADDRESS_BLACKLISTED",
                  "description":"DND blacklisted","permanent":true}}]}""");
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
    void aDeliveredWhatsAppIsTriedAsWhatsAppFirstAndNoErrorIsLeftOut() throws Exception {
        ReflectionTestUtils.setField(controller, "webhookKey", "k-123");
        InfobipDtos.Reports body = read("""
                {"results":[{"messageId":"wa-1","to":"2349035911994","channel":"WHATSAPP","sentAt":"2026-11-10T09:12:01.000+0000",
                 "status":{"groupId":3,"groupName":"DELIVERED","id":5,"name":"DELIVERED_TO_HANDSET","description":"Message delivered to handset"},
                 "error":{"groupId":0,"groupName":"OK","id":0,"name":"NO_ERROR","description":"No Error","permanent":false}},
                 {"to":"2349035911994","status":{"groupName":"DELIVERED"}}]}""");
        assertThat(controller.reports("k-123", body).getStatusCode().value()).isEqualTo(200);
        InOrder order = inOrder(dispatcher);
        order.verify(dispatcher).applyReport("whatsapp:wa-1", "DELIVERED", "Message delivered to handset (DELIVERED_TO_HANDSET)");
        order.verify(dispatcher).applyReport("sms:wa-1", "DELIVERED", "Message delivered to handset (DELIVERED_TO_HANDSET)");
    }

    @Test
    void postsWithoutTheKeyAreRefused() throws Exception {
        ReflectionTestUtils.setField(controller, "webhookKey", "k-123");
        assertThat(controller.reports("wrong", dndReport()).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.reports(null, dndReport()).getStatusCode().value()).isEqualTo(401);
        verify(dispatcher, never()).applyReport(anyString(), anyString(), anyString());
    }
}

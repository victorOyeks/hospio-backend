package com.hospio.folio.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

/** 18 Nov 2026 audit: the folio (stay summary) has no WhatsApp template, so WhatsApp is refused in words, never queued to fail. */
class FolioWhatsAppTest {

    @Test
    void aFolioByWhatsAppIsRefusedAndNothingIsSent() {
        FolioService folios = mock(FolioService.class);
        DeliveryService deliveries = mock(DeliveryService.class);
        OutboundService outbound = new OutboundService(folios, mock(OrganizationService.class), deliveries);
        assertThatThrownBy(() -> outbound.sendFolio("f1", "WHATSAPP", "+2349035911994"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("The folio can't go on WhatsApp yet (no approved WhatsApp template). Email it or print it.");
        verify(deliveries, never()).send(any(Delivery.class));
        verify(folios, never()).find(any());
    }
}

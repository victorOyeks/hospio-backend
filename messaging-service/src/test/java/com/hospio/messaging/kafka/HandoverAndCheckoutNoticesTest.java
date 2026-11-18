package com.hospio.messaging.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.NotificationChannel;
import com.hospio.common.kafka.events.GuestCheckedOutEvent;
import com.hospio.common.kafka.events.HandoverAcceptedEvent;
import com.hospio.common.tenancy.TenantBinder;
import com.hospio.messaging.client.IdentityClient;
import com.hospio.messaging.mail.MailDispatcher;
import com.hospio.messaging.service.NotificationService;
import com.hospio.messaging.service.OutboundService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 18 Nov 2026 audit: a check-out never queues a WhatsApp stay summary; an accepted handover reaches the platform's bell. */
class HandoverAndCheckoutNoticesTest {

    private final NotificationService notifications = mock(NotificationService.class);
    private final OutboundService outbound = mock(OutboundService.class);
    private final MessagingEventListener listener = new MessagingEventListener(notifications, outbound, mock(TenantBinder.class),
            mock(MailDispatcher.class));

    @Test
    void aCheckOutSendsTheStaySummaryByEmailOnlyEvenWhenWhatsAppIsAskedFor() {
        when(outbound.render(any(), any(), any(), any(), any())).thenReturn("Thank you");
        GuestCheckedOutEvent e = GuestCheckedOutEvent.builder().eventId("e1").hotelId("h1").bookingId("b1").guestName("Ada")
                .guestPhone("+2349035911994").guestEmail("ada@hospio-qa.test").roomNumber("101").folioBalance(BigDecimal.ZERO)
                .summaryChannels(List.of("WHATSAPP", "EMAIL")).build();
        listener.onCheckedOut(e);
        verify(outbound).queue(eq(NotificationChannel.EMAIL), eq("ada@hospio-qa.test"), anyString(), anyString(), eq("STAY_SUMMARY"), anyString());
        verify(outbound, never()).queue(eq(NotificationChannel.WHATSAPP), any(), any(), any(), any(), any());

        // A guest with a phone and no email: nothing is queued at all, so nothing can fail.
        listener.onCheckedOut(GuestCheckedOutEvent.builder().eventId("e2").hotelId("h1").bookingId("b2").guestName("Bola")
                .guestPhone("+2349035911994").folioBalance(BigDecimal.ZERO).summaryChannels(List.of("WHATSAPP")).build());
        verify(outbound, times(1)).queue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anAcceptedHandoverReachesTheAccountManagerAndEverySuperAdminOnce() {
        IdentityClient identity = mock(IdentityClient.class);
        when(identity.platformStaff("SUPER_ADMIN")).thenReturn(List.of(
                new IdentityClient.Contact("sa-1", "Amoria", "amoria@hospio360.com", null, null, "ACTIVE", List.of("SUPER_ADMIN")),
                new IdentityClient.Contact("am-1", "Kemi", "kemi@hospio360.com", null, null, "ACTIVE", List.of("SUPER_ADMIN"))));
        listener.setIdentity(identity);
        HandoverAcceptedEvent e = HandoverAcceptedEvent.builder().eventId("ev-h").businessId("biz-1").businessName("QA Hotels")
                .ownerName("Tunde Bakare").accountManagerId("am-1").build();

        listener.onHandoverAccepted(e);

        verify(notifications).notify(eq("ev-h"), eq("am-1"), eq(null), eq("HANDOVER_ACCEPTED"), eq("QA Hotels accepted the handover"),
                eq("Tunde Bakare accepted the handover. QA Hotels is live; a welcome email went to the owner."), eq("/dashboard/businesses/biz-1"));
        verify(notifications).notify(eq("ev-h"), eq("sa-1"), eq(null), eq("HANDOVER_ACCEPTED"), anyString(), anyString(), anyString());
        verify(notifications, times(2)).notify(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void identityDownStillTellsTheAccountManager() {
        IdentityClient identity = mock(IdentityClient.class);
        when(identity.platformStaff(any())).thenThrow(new IllegalStateException("down"));
        listener.setIdentity(identity);
        listener.onHandoverAccepted(HandoverAcceptedEvent.builder().eventId("ev-2").businessId("biz-2").businessName("B").accountManagerId("am-2").build());
        verify(notifications).notify(eq("ev-2"), eq("am-2"), eq(null), eq("HANDOVER_ACCEPTED"), anyString(), anyString(), anyString());
    }
}

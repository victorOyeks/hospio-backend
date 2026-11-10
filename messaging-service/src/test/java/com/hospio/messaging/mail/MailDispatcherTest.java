package com.hospio.messaging.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.kafka.events.DeliveryRequestedEvent;
import com.hospio.messaging.client.TenantClient;
import com.hospio.messaging.entity.DeliveryRecord;
import com.hospio.messaging.entity.Message;
import com.hospio.messaging.repository.DeliveryRecordRepository;
import com.hospio.messaging.repository.MessageRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The one sender: who it is from, what is recorded, and that a failure never escapes (docs/email.md). */
class MailDispatcherTest {

    private final Map<String, DeliveryRecord> rows = new HashMap<>();
    private final DeliveryRecordRepository records = mock(DeliveryRecordRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final TenantClient tenants = mock(TenantClient.class);
    private final SmtpGateway smtp = mock(SmtpGateway.class);
    private MailDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(records.save(any())).thenAnswer(i -> {
            DeliveryRecord r = i.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID().toString());
            }
            rows.put(r.getId(), r);
            return r;
        });
        when(records.findById(any())).thenAnswer(i -> Optional.ofNullable(rows.get(i.<String>getArgument(0))));
        when(records.findByEventId(any())).thenAnswer(i -> rows.values().stream().filter(r -> i.getArgument(0).equals(r.getEventId())).findFirst());
        when(tenants.hotel("h-royal")).thenReturn(Optional.of(new TenantClient.Sender("Royal Suites Ikeja", "frontdesk@royal.example")));
        when(tenants.hotel("h-b1")).thenReturn(Optional.of(new TenantClient.Sender("QA Owner B Lekki", null)));
        when(tenants.business("b-b")).thenReturn(Optional.of(new TenantClient.Sender("QA Owner B Hotels", "owner-b@hotels.example")));
        when(tenants.business("b-only")).thenReturn(Optional.of(new TenantClient.Sender("Only Business Ltd", null)));
        dispatcher = dispatcher(true);
    }

    private MailDispatcher dispatcher(boolean enabled) {
        MailDispatcher d = new MailDispatcher(records, messages, tenants, new EmailTemplates(), smtp,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), enabled, "no-reply@sender.example", "Hospio360", 1, 30);
        d.runInline();
        return d;
    }

    private static DeliveryRequestedEvent email(String business, String hotel, String to) {
        return DeliveryRequestedEvent.builder().eventId(UUID.randomUUID().toString()).businessId(business).hotelId(hotel).channel("EMAIL").to(to)
                .subject("You're invited to Hospio360").text("Hello Ada,\n\nOpen https://web.example/accept-invite?token=abc to sign in.").kind("INVITE").ref("u1").build();
    }

    private SmtpGateway.Email sent() {
        ArgumentCaptor<SmtpGateway.Email> c = ArgumentCaptor.forClass(SmtpGateway.Email.class);
        verify(smtp).send(c.capture());
        return c.getValue();
    }

    @Test
    void fromNameIsTheHotelsNameAndReplyToItsEmail() {
        when(smtp.send(any())).thenReturn(new SmtpGateway.Accepted("<m1@sender>", "250 2.0.0 OK: queued as <x1>"));
        DeliveryRecord r = dispatcher.accept(email("b-royal", "h-royal", "ada@guest.test"));
        SmtpGateway.Email m = sent();
        assertThat(m.fromName()).isEqualTo("Royal Suites Ikeja");
        assertThat(m.fromAddress()).isEqualTo("no-reply@sender.example");
        assertThat(m.replyTo()).isEqualTo("frontdesk@royal.example");
        assertThat(m.html()).contains("Royal Suites Ikeja").contains("Accept the invite").contains("https://web.example/accept-invite?token=abc");
        assertThat(m.text()).contains("Hello Ada");
        assertThat(rows.get(r.getId()).getStatus()).isEqualTo(DeliveryRecord.Status.SENT);
        assertThat(rows.get(r.getId()).getProviderMessageId()).contains("<m1@sender>").contains("250");
        assertThat(rows.get(r.getId()).getFromName()).isEqualTo("Royal Suites Ikeja");
    }

    @Test
    void twoHotelsOfOneOwnerEachSendInTheirOwnName() {
        when(smtp.send(any())).thenReturn(new SmtpGateway.Accepted("<m>", "250 OK"));
        when(tenants.hotel("h-b2")).thenReturn(Optional.of(new TenantClient.Sender("QA Owner B Ikoyi", "ikoyi@hotels.example")));
        dispatcher.accept(email("b-b", "h-b1", "x@a.test"));
        dispatcher.accept(email("b-b", "h-b2", "y@a.test"));
        ArgumentCaptor<SmtpGateway.Email> c = ArgumentCaptor.forClass(SmtpGateway.Email.class);
        verify(smtp, times(2)).send(c.capture());
        assertThat(c.getAllValues()).extracting(SmtpGateway.Email::fromName).containsExactly("QA Owner B Lekki", "QA Owner B Ikoyi");
        // a hotel without its own email replies to the business
        assertThat(c.getAllValues().get(0).replyTo()).isEqualTo("owner-b@hotels.example");
        assertThat(c.getAllValues().get(1).replyTo()).isEqualTo("ikoyi@hotels.example");
    }

    @Test
    void noHotelUsesTheBusinessThenThePlatform() {
        when(smtp.send(any())).thenReturn(new SmtpGateway.Accepted("<m>", "250 OK"));
        assertThat(dispatcher.senderFor("b-only", null, null, null)).isEqualTo(new MailDispatcher.From("Only Business Ltd", null));
        assertThat(dispatcher.senderFor(null, null, null, null)).isEqualTo(new MailDispatcher.From("Hospio360", null));
        assertThat(dispatcher.senderFor("b-only", "h-royal", "Override", "r@x.example")).isEqualTo(new MailDispatcher.From("Override", "r@x.example"));
    }

    @Test
    void everyAddressIsSentWhateverTheDomain() {
        when(smtp.send(any())).thenReturn(new SmtpGateway.Accepted("<m>", "250 OK"));
        dispatcher.accept(email("b-royal", "h-royal", "tunde@royalsuites.ng"));
        dispatcher.accept(email("b-royal", "h-royal", "qa@hospio-qa.test"));
        dispatcher.accept(email("b-royal", "h-royal", "someone@example.com"));
        verify(smtp, times(3)).send(any());
    }

    @Test
    void aRefusalIsRecordedAsFailedAndNeverThrows() {
        when(smtp.send(any())).thenThrow(new SmtpGateway.SendFailure("550 5.1.1 Recipient address rejected", false, null));
        DeliveryRecord r = dispatcher.accept(email("b-royal", "h-royal", "nobody@nowhere.test"));
        assertThat(rows.get(r.getId()).getStatus()).isEqualTo(DeliveryRecord.Status.FAILED);
        assertThat(rows.get(r.getId()).getError()).contains("550");
        assertThat(rows.get(r.getId()).getAttempts()).isEqualTo(1);
        verify(smtp, times(1)).send(any());
    }

    @Test
    void aTransientFailureIsTriedOnceMore() {
        when(smtp.send(any())).thenThrow(new SmtpGateway.SendFailure("Couldn't connect to host, port: smtp.example, 2526", true, null))
                .thenReturn(new SmtpGateway.Accepted("<m2>", "250 OK"));
        DeliveryRecord r = dispatcher.accept(email("b-royal", "h-royal", "ada@gmail.example"));
        verify(smtp, times(2)).send(any());
        assertThat(rows.get(r.getId()).getStatus()).isEqualTo(DeliveryRecord.Status.SENT);
        assertThat(rows.get(r.getId()).getAttempts()).isEqualTo(2);
    }

    @Test
    void twoTransientFailuresGiveUpAfterTheRetry() {
        when(smtp.send(any())).thenThrow(new SmtpGateway.SendFailure("Read timed out", true, null));
        DeliveryRecord r = dispatcher.accept(email("b-royal", "h-royal", "ada@gmail.example"));
        verify(smtp, times(2)).send(any());
        assertThat(rows.get(r.getId()).getStatus()).isEqualTo(DeliveryRecord.Status.FAILED);
        assertThat(rows.get(r.getId()).getError()).isEqualTo("Read timed out");
    }

    @Test
    void anUnexpectedErrorInTheSenderDoesNotEscape() {
        when(smtp.send(any())).thenThrow(new IllegalStateException("boom"));
        DeliveryRecord r = dispatcher.accept(email("b-royal", "h-royal", "ada@gmail.example"));
        assertThat(rows.get(r.getId()).getStatus()).isEqualTo(DeliveryRecord.Status.FAILED);
    }

    @Test
    void smsAndWhatsAppAreRecordedNotSent() {
        DeliveryRecord r = dispatcher.accept(DeliveryRequestedEvent.builder().eventId("e-sms").businessId("b-royal").hotelId("h-royal").channel("SMS")
                .to("+2348030000000").text("Your code is 123456").kind("PHONE_CODE").build());
        assertThat(r.getStatus()).isEqualTo(DeliveryRecord.Status.NOT_CONNECTED);
        verify(smtp, never()).send(any());
    }

    @Test
    void switchedOffRecordsWithoutSending() {
        DeliveryRecord r = dispatcher(false).accept(email("b-royal", "h-royal", "ada@gmail.example"));
        assertThat(r.getStatus()).isEqualTo(DeliveryRecord.Status.DISABLED);
        verify(smtp, never()).send(any());
    }

    @Test
    void aRedeliveredEventIsNotSentTwice() {
        when(smtp.send(any())).thenReturn(new SmtpGateway.Accepted("<m>", "250 OK"));
        DeliveryRequestedEvent e = email("b-royal", "h-royal", "ada@gmail.example");
        dispatcher.accept(e);
        assertThat(dispatcher.accept(e)).isNull();
        verify(smtp, times(1)).send(any());
    }

    @Test
    void aGuestMessageFollowsItsDelivery() {
        Message m = Message.builder().direction(Message.Direction.OUT).body("Hi").status("QUEUED").build();
        m.setId("msg-1");
        when(messages.findById("msg-1")).thenReturn(Optional.of(m));
        when(smtp.send(any())).thenThrow(new SmtpGateway.SendFailure("550 rejected", false, null));
        dispatcher.accept(DeliveryRequestedEvent.builder().eventId("e-msg").businessId("b-royal").hotelId("h-royal").channel("EMAIL")
                .to("guest@nowhere.test").subject("Hello").text("Hi").kind("MESSAGE").ref("msg-1").build());
        assertThat(m.getStatus()).isEqualTo("FAILED");
        assertThat(m.getError()).contains("550");
    }

    @Test
    void anOldEventIsNeverSent() {
        DeliveryRequestedEvent e = email("b-royal", "h-royal", "ada@gmail.example");
        e.setOccurredAt(java.time.Instant.now().minus(java.time.Duration.ofHours(3)));
        assertThat(dispatcher.accept(e)).isNull();
        verify(smtp, never()).send(any());
        assertThat(rows).isEmpty();
    }
}

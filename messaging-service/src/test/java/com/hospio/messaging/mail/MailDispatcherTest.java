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
        // The queue as Postgres keeps it: due QUEUED rows, leased (attempts + 1) when claimed.
        when(records.lockDue(any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(i -> {
            java.time.Instant now = i.getArgument(0);
            return rows.values().stream().filter(r -> r.getStatus() == DeliveryRecord.Status.QUEUED && r.getNextAttemptAt() != null
                    && !r.getNextAttemptAt().isAfter(now)).map(DeliveryRecord::getId).limit(i.<Integer>getArgument(1)).toList();
        });
        when(records.lease(any(), any())).thenAnswer(i -> {
            java.util.Collection<String> ids = i.getArgument(0);
            ids.forEach(id -> {
                rows.get(id).setNextAttemptAt(i.getArgument(1));
                rows.get(id).setAttempts(rows.get(id).getAttempts() + 1);
            });
            return ids.size();
        });
        when(tenants.hotel("h-royal")).thenReturn(Optional.of(new TenantClient.Sender("Royal Suites Ikeja", "frontdesk@royal.example")));
        when(tenants.hotel("h-b1")).thenReturn(Optional.of(new TenantClient.Sender("QA Owner B Lekki", null)));
        when(tenants.business("b-b")).thenReturn(Optional.of(new TenantClient.Sender("QA Owner B Hotels", "owner-b@hotels.example")));
        when(tenants.business("b-only")).thenReturn(Optional.of(new TenantClient.Sender("Only Business Ltd", null)));
        dispatcher = dispatcher(true);
    }

    private MailDispatcher dispatcher(boolean enabled) {
        MailDispatcher d = new MailDispatcher(records, messages, tenants, new EmailTemplates(), smtp,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), enabled, "no-reply@sender.example", "Hospio360", 0, 30, 2, 48);
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
        DeliveryRecord r = dispatcher.accept(email("b-royal", "h-royal", "ada@guestmail.ng"));
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
        dispatcher.accept(email("b-b", "h-b1", "x@a-hotel.ng"));
        dispatcher.accept(email("b-b", "h-b2", "y@a-hotel.ng"));
        ArgumentCaptor<SmtpGateway.Email> c = ArgumentCaptor.forClass(SmtpGateway.Email.class);
        verify(smtp, times(2)).send(c.capture());
        assertThat(c.getAllValues()).extracting(SmtpGateway.Email::fromName).containsExactly("QA Owner B Lekki", "QA Owner B Ikoyi");
        // a hotel without its own email replies to the business
        assertThat(c.getAllValues().get(0).replyTo()).isEqualTo("owner-b@hotels.example");
        assertThat(c.getAllValues().get(1).replyTo()).isEqualTo("ikoyi@hotels.example");
    }

    @Test
    void aHotelEmailThatIsNotAnAddressIsNeverTheReplyTo() {
        // Staging 10 Nov: hotel "thewiseman" saved its email as "0000000" and every invite from it failed "Missing final '@domain'".
        when(smtp.send(any())).thenReturn(new SmtpGateway.Accepted("<m>", "250 OK"));
        when(tenants.hotel("h-bad")).thenReturn(Optional.of(new TenantClient.Sender("thewiseman", "0000000")));
        when(tenants.hotel("h-half")).thenReturn(Optional.of(new TenantClient.Sender("Wuse", "wuse@")));
        when(tenants.business("b-bad")).thenReturn(Optional.of(new TenantClient.Sender("0000", "0000")));
        assertThat(dispatcher.senderFor("b-b", "h-bad", null, null)).isEqualTo(new MailDispatcher.From("thewiseman", "owner-b@hotels.example"));
        assertThat(dispatcher.senderFor("b-bad", "h-half", null, null)).isEqualTo(new MailDispatcher.From("Wuse", null));
        assertThat(dispatcher.senderFor("b-b", "h-royal", null, "not an address")).isEqualTo(new MailDispatcher.From("Royal Suites Ikeja", "frontdesk@royal.example"));

        DeliveryRecord r = dispatcher.accept(email("b-bad", "h-bad", "sak@gmail.com"));
        assertThat(sent().replyTo()).isNull();
        assertThat(rows.get(r.getId()).getStatus()).isEqualTo(DeliveryRecord.Status.SENT);
    }

    @Test
    void addressesTheProviderTakes() {
        assertThat(MailDispatcher.address("frontdesk@royal.example")).isTrue();
        assertThat(MailDispatcher.address(" a.b+c@sub.hotel.ng ")).isTrue();
        assertThat(MailDispatcher.address("0000000")).isFalse();
        assertThat(MailDispatcher.address("wuse@")).isFalse();
        assertThat(MailDispatcher.address("x@localhost")).isFalse();
        assertThat(MailDispatcher.address("a b@c.com")).isFalse();
        assertThat(MailDispatcher.address("a@b.c")).isFalse();
        assertThat(MailDispatcher.address(null)).isFalse();
    }

    @Test
    void noHotelUsesTheBusinessThenThePlatform() {
        when(smtp.send(any())).thenReturn(new SmtpGateway.Accepted("<m>", "250 OK"));
        assertThat(dispatcher.senderFor("b-only", null, null, null)).isEqualTo(new MailDispatcher.From("Only Business Ltd", null));
        assertThat(dispatcher.senderFor(null, null, null, null)).isEqualTo(new MailDispatcher.From("Hospio360", null));
        assertThat(dispatcher.senderFor("b-only", "h-royal", "Override", "r@x.example")).isEqualTo(new MailDispatcher.From("Override", "r@x.example"));
    }

    @Test
    void testAddressesAreRecordedSkippedAndNeverReachTheProvider() {
        when(smtp.send(any())).thenReturn(new SmtpGateway.Accepted("<m>", "250 OK"));
        DeliveryRecord real = dispatcher.accept(email("b-royal", "h-royal", "tunde@royalsuites.ng"));
        java.util.List<DeliveryRecord> skipped = java.util.stream.Stream.of("qa@hospio-qa.test", "someone@example.com", "x@mail.example.org",
                "a@b.example", "a@host.invalid", "a@localhost", "a@printer.local", "A@Example.NET").map(to -> dispatcher.accept(email("b-royal", "h-royal", to))).toList();
        verify(smtp, times(1)).send(any());
        assertThat(real.getStatus()).isEqualTo(DeliveryRecord.Status.SENT);
        assertThat(skipped).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo(DeliveryRecord.Status.SKIPPED);
            assertThat(r.getError()).isEqualTo("Not sent: test address");
            assertThat(r.getBodyText()).isNull();
            assertThat(r.isBillable()).isFalse();
        });
        // Even with email switched off, a test address says why it was not sent.
        assertThat(dispatcher(false).accept(email("b-royal", "h-royal", "qa@hospio-qa.test")).getStatus()).isEqualTo(DeliveryRecord.Status.SKIPPED);
    }

    @Test
    void aTestAddressQueuedBeforeTheRuleIsSkippedNotSent() {
        DeliveryRecord queued = DeliveryRecord.builder().channel("EMAIL").toAddress("ada@guest.test").subject("Your receipt").kind("RECEIPT")
                .status(DeliveryRecord.Status.QUEUED).bodyText("Thank you").nextAttemptAt(java.time.Instant.now().minusSeconds(1)).build();
        records.save(queued);
        dispatcher.drain();
        verify(smtp, never()).send(any());
        assertThat(rows.get(queued.getId()).getStatus()).isEqualTo(DeliveryRecord.Status.SKIPPED);
        assertThat(rows.get(queued.getId()).getError()).isEqualTo("Not sent: test address");
    }

    @Test
    void aRefusalIsRecordedAsFailedAndNeverThrows() {
        when(smtp.send(any())).thenThrow(new SmtpGateway.SendFailure("550 5.1.1 Recipient address rejected", false, null));
        DeliveryRecord r = dispatcher.accept(email("b-royal", "h-royal", "nobody@nowhere.ng"));
        assertThat(rows.get(r.getId()).getStatus()).isEqualTo(DeliveryRecord.Status.FAILED);
        assertThat(rows.get(r.getId()).getError()).contains("550");
        assertThat(rows.get(r.getId()).getAttempts()).isEqualTo(1);
        verify(smtp, times(1)).send(any());
    }

    @Test
    void aTransientFailureIsTriedOnceMore() {
        when(smtp.send(any())).thenThrow(new SmtpGateway.SendFailure("Couldn't connect to host, port: smtp.example, 2526", true, null))
                .thenReturn(new SmtpGateway.Accepted("<m2>", "250 OK"));
        DeliveryRecord r = dispatcher.accept(email("b-royal", "h-royal", "ada@gmail.com"));
        verify(smtp, times(2)).send(any());
        assertThat(rows.get(r.getId()).getStatus()).isEqualTo(DeliveryRecord.Status.SENT);
        assertThat(rows.get(r.getId()).getAttempts()).isEqualTo(2);
    }

    @Test
    void twoTransientFailuresGiveUpAfterTheRetry() {
        when(smtp.send(any())).thenThrow(new SmtpGateway.SendFailure("Read timed out", true, null));
        DeliveryRecord r = dispatcher.accept(email("b-royal", "h-royal", "ada@gmail.com"));
        verify(smtp, times(2)).send(any());
        assertThat(rows.get(r.getId()).getStatus()).isEqualTo(DeliveryRecord.Status.FAILED);
        assertThat(rows.get(r.getId()).getError()).isEqualTo("Read timed out");
    }

    @Test
    void anUnexpectedErrorInTheSenderDoesNotEscape() {
        when(smtp.send(any())).thenThrow(new IllegalStateException("boom"));
        DeliveryRecord r = dispatcher.accept(email("b-royal", "h-royal", "ada@gmail.com"));
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
    void anSmsThatIsNotACodeIsRecordedAndNeverSentEvenWithSmsOn() {
        // SMS is only for one-time codes (docs/sms-whatsapp.md): a receipt by SMS is refused with the reason.
        com.hospio.messaging.text.TextSender texts = mock(com.hospio.messaging.text.TextSender.class);
        when(texts.live(any(), any())).thenReturn(true);
        dispatcher.setTexts(texts);
        DeliveryRecord r = dispatcher.accept(DeliveryRequestedEvent.builder().eventId("e-sms-r").businessId("b-royal").hotelId("h-royal").channel("SMS")
                .to("+2348030000000").text("Thanks for your payment").kind("RECEIPT").build());
        assertThat(r.getStatus()).isEqualTo(DeliveryRecord.Status.NOT_CONNECTED);
        assertThat(r.getError()).contains("SMS is only used for sign-in codes");
        verify(texts, never()).send(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void switchedOffRecordsWithoutSending() {
        DeliveryRecord r = dispatcher(false).accept(email("b-royal", "h-royal", "ada@gmail.com"));
        assertThat(r.getStatus()).isEqualTo(DeliveryRecord.Status.DISABLED);
        verify(smtp, never()).send(any());
    }

    @Test
    void aRedeliveredEventIsNotSentTwice() {
        when(smtp.send(any())).thenReturn(new SmtpGateway.Accepted("<m>", "250 OK"));
        DeliveryRequestedEvent e = email("b-royal", "h-royal", "ada@gmail.com");
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
                .to("guest@nowhere.ng").subject("Hello").text("Hi").kind("MESSAGE").ref("msg-1").build());
        assertThat(m.getStatus()).isEqualTo("FAILED");
        assertThat(m.getError()).contains("550");
    }

    @Test
    void anOldEventIsNeverSent() {
        DeliveryRequestedEvent e = email("b-royal", "h-royal", "ada@gmail.com");
        e.setOccurredAt(java.time.Instant.now().minus(java.time.Duration.ofHours(3)));
        assertThat(dispatcher.accept(e)).isNull();
        verify(smtp, never()).send(any());
        assertThat(rows).isEmpty();
    }

    @Test
    void acceptOnlyRecordsTheQueuedEmailWithoutCallingAnyService() {
        dispatcher.runOn(r -> { }); // no worker
        DeliveryRecord r = dispatcher.accept(email("b-royal", "h-royal", "ada@guestmail.ng"));
        assertThat(r.getStatus()).isEqualTo(DeliveryRecord.Status.QUEUED);
        assertThat(r.getBodyText()).contains("Hello Ada");
        assertThat(r.getNextAttemptAt()).isNotNull();
        verify(smtp, never()).send(any());
        org.mockito.Mockito.verifyNoInteractions(tenants);

        when(smtp.send(any())).thenReturn(new SmtpGateway.Accepted("<m>", "250 OK"));
        dispatcher.drain(); // a worker, later
        assertThat(rows.get(r.getId()).getStatus()).isEqualTo(DeliveryRecord.Status.SENT);
        assertThat(rows.get(r.getId()).getFromName()).isEqualTo("Royal Suites Ikeja");
        assertThat(rows.get(r.getId()).getBodyText()).isNull(); // what was sent is not kept
        assertThat(rows.get(r.getId()).getNextAttemptAt()).isNull();
    }

    @Test
    void aRowLeftByAWorkerThatDiedIsSentWhenItsLeaseRunsOut() {
        DeliveryRecord left = DeliveryRecord.builder().channel("EMAIL").toAddress("ada@guestmail.ng").subject("Your receipt").kind("RECEIPT")
                .status(DeliveryRecord.Status.QUEUED).attempts(1).bodyText("Thank you").nextAttemptAt(java.time.Instant.now().minusSeconds(1)).build();
        left.setId("left");
        rows.put("left", left);
        DeliveryRecord leased = DeliveryRecord.builder().channel("EMAIL").toAddress("bo@guestmail.ng").kind("RECEIPT").status(DeliveryRecord.Status.QUEUED)
                .attempts(1).bodyText("Thank you").nextAttemptAt(java.time.Instant.now().plusSeconds(240)).build();
        leased.setId("leased");
        rows.put("leased", leased);
        when(smtp.send(any())).thenReturn(new SmtpGateway.Accepted("<m>", "250 OK"));
        dispatcher.drain();
        assertThat(rows.get("left").getStatus()).isEqualTo(DeliveryRecord.Status.SENT);
        assertThat(rows.get("left").getAttempts()).isEqualTo(2);
        assertThat(rows.get("leased").getStatus()).isEqualTo(DeliveryRecord.Status.QUEUED); // still another worker's
        verify(smtp, times(1)).send(any());
    }

    @Test
    void aRowQueuedBeforeTheQueueKeptBodiesIsFailedNotSent() {
        DeliveryRecord old = DeliveryRecord.builder().channel("EMAIL").toAddress("ada@guestmail.ng").kind("RECEIPT").status(DeliveryRecord.Status.QUEUED)
                .nextAttemptAt(java.time.Instant.now().minusSeconds(1)).build();
        old.setId("old");
        rows.put("old", old);
        dispatcher.drain();
        assertThat(rows.get("old").getStatus()).isEqualTo(DeliveryRecord.Status.FAILED);
        assertThat(rows.get("old").getError()).contains("Interrupted");
        verify(smtp, never()).send(any());
    }

    @Test
    void aCodeStillQueuedAfterItsAgeIsNotSentButAReceiptIs() {
        java.time.Instant hourAgo = java.time.Instant.now().minus(java.time.Duration.ofHours(1));
        DeliveryRecord code = DeliveryRecord.builder().channel("EMAIL").toAddress("ada@guestmail.ng").kind("TEMP_PASSWORD").status(DeliveryRecord.Status.QUEUED)
                .bodyText("Temporary password: x").nextAttemptAt(hourAgo).build();
        code.setId("code");
        code.setCreatedAt(hourAgo);
        rows.put("code", code);
        DeliveryRecord receipt = DeliveryRecord.builder().channel("EMAIL").toAddress("ada@guestmail.ng").kind("RECEIPT").status(DeliveryRecord.Status.QUEUED)
                .bodyText("Thank you").nextAttemptAt(hourAgo).build();
        receipt.setId("receipt");
        receipt.setCreatedAt(hourAgo);
        rows.put("receipt", receipt);
        when(smtp.send(any())).thenReturn(new SmtpGateway.Accepted("<m>", "250 OK"));
        dispatcher.drain();
        assertThat(rows.get("code").getStatus()).isEqualTo(DeliveryRecord.Status.FAILED);
        assertThat(rows.get("code").getBodyText()).isNull();
        assertThat(rows.get("receipt").getStatus()).isEqualTo(DeliveryRecord.Status.SENT);
        verify(smtp, times(1)).send(any());
    }

    @Test
    void anOldReceiptEventIsStillSentAfterConsumerLag() {
        when(smtp.send(any())).thenReturn(new SmtpGateway.Accepted("<m>", "250 OK"));
        DeliveryRequestedEvent e = DeliveryRequestedEvent.builder().eventId("e-late").businessId("b-royal").hotelId("h-royal").channel("EMAIL")
                .to("ada@guestmail.ng").subject("Your receipt").text("Thank you").kind("RECEIPT").occurredAt(java.time.Instant.now().minus(java.time.Duration.ofHours(3)))
                .build();
        assertThat(dispatcher.accept(e)).isNotNull();
        verify(smtp).send(any());
        DeliveryRequestedEvent replayed = DeliveryRequestedEvent.builder().eventId("e-replay").channel("EMAIL").to("ada@guestmail.ng").text("Thank you")
                .kind("RECEIPT").occurredAt(java.time.Instant.now().minus(java.time.Duration.ofDays(5))).build();
        assertThat(dispatcher.accept(replayed)).isNull(); // a replayed topic, days old
    }

    @Test
    void aFinishedRowIsNotSentAgain() {
        DeliveryRecord done = DeliveryRecord.builder().channel("EMAIL").toAddress("ada@guestmail.ng").kind("RECEIPT").status(DeliveryRecord.Status.SENT)
                .bodyText("x").build();
        done.setId("done");
        rows.put("done", done);
        dispatcher.process("done");
        verify(smtp, never()).send(any());
    }

    @Test
    void aWhatsAppKeepsItsTemplateValuesUntilTheWorkerSendsIt() {
        com.hospio.messaging.text.TextSender texts = mock(com.hospio.messaging.text.TextSender.class);
        when(texts.live(org.mockito.ArgumentMatchers.eq("WHATSAPP"), any())).thenReturn(true);
        when(texts.send(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any())).thenReturn(new com.hospio.messaging.text.TextSender.Outcome(true, "WHATSAPP", "wa-1", null));
        dispatcher.setTexts(texts);
        dispatcher.accept(DeliveryRequestedEvent.builder().eventId("e-wa").businessId("b-royal").hotelId("h-royal").channel("WHATSAPP").to("+2348030000000")
                .text("Thanks from @hotel").kind("RECEIPT").template("hospio_payment_receipt").params(java.util.List.of("Ada", "@hotel", "₦5,000")).build());
        verify(texts).send("WHATSAPP", "+2348030000000", "Thanks from Royal Suites Ikeja", "hospio_payment_receipt",
                java.util.List.of("Ada", "Royal Suites Ikeja", "₦5,000"), false, null);
    }

    @Test
    void aWhatsAppWithNoTemplateIsSkippedNeverQueuedNorFailed() {
        // Stay summary, folio, a typed guest message, a staff notice: no approved template (common WhatsAppTemplates).
        com.hospio.messaging.text.TextSender texts = mock(com.hospio.messaging.text.TextSender.class);
        when(texts.live(any(), any())).thenReturn(true);
        when(texts.send(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(new com.hospio.messaging.text.TextSender.Outcome(true, "WHATSAPP", "whatsapp:wa-9", null));
        dispatcher.setTexts(texts);
        for (String kind : java.util.List.of("STAY_SUMMARY", "FOLIO", "MESSAGE", "NOTICE")) {
            DeliveryRecord r = dispatcher.accept(DeliveryRequestedEvent.builder().eventId("e-nt-" + kind).businessId("b-royal").hotelId("h-royal")
                    .channel("WHATSAPP").to("+2348030000000").text("Thank you for staying").kind(kind).build());
            assertThat(r.getStatus()).as(kind).isEqualTo(DeliveryRecord.Status.SKIPPED);
            assertThat(r.getError()).as(kind).isEqualTo(com.hospio.common.delivery.WhatsAppTemplates.NO_TEMPLATE);
            assertThat(r.getNextAttemptAt()).as(kind).isNull();
            assertThat(r.isBillable()).as(kind).isFalse();
        }
        // A receipt still goes, queued with its template.
        DeliveryRecord receipt = dispatcher.accept(DeliveryRequestedEvent.builder().eventId("e-nt-r").businessId("b-royal").hotelId("h-royal")
                .channel("WHATSAPP").to("+2348030000000").text("Thanks").kind("RECEIPT").template("hospio_payment_receipt_v2")
                .params(java.util.List.of("Ada")).build());
        assertThat(rows.get(receipt.getId()).getStatus()).isEqualTo(DeliveryRecord.Status.SENT);
        verify(texts).send(org.mockito.ArgumentMatchers.eq("WHATSAPP"), any(), any(), org.mockito.ArgumentMatchers.eq("hospio_payment_receipt_v2"),
                any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    private DeliveryRecord sentEmail(String providerId) {
        DeliveryRecord r = DeliveryRecord.builder().channel("EMAIL").toAddress("qa.msg@hospio-qa.test").kind("INVITE")
                .status(DeliveryRecord.Status.SENT).providerMessageId(providerId).build();
        r.setId(UUID.randomUUID().toString());
        rows.put(r.getId(), r);
        when(records.findEmailByMessageId(any(), any())).thenAnswer(i -> {
            String id = i.getArgument(0);
            return rows.values().stream().filter(x -> "EMAIL".equals(x.getChannel()) && x.getProviderMessageId() != null
                    && (x.getProviderMessageId().equals(id) || x.getProviderMessageId().startsWith(id + " "))).findFirst();
        });
        return r;
    }

    @Test
    void brevosEventsMoveAnEmailToDeliveredOrFailedWithTheReason() {
        DeliveryRecord bounced = sentEmail("<m1@smtp-relay.mailin.fr> · Brevo API 201");
        java.time.Instant at = java.time.Instant.parse("2026-11-18T11:00:00Z");
        assertThat(dispatcher.applyEmailEvent("<m1@smtp-relay.mailin.fr>", "soft_bounce", "Unable to find MX of domain hospio-qa.test", at)).isTrue();
        assertThat(bounced.getStatus()).isEqualTo(DeliveryRecord.Status.FAILED);
        assertThat(bounced.getError()).isEqualTo("Bounced (soft): Unable to find MX of domain hospio-qa.test");
        assertThat(bounced.getProviderEvent()).isEqualTo("soft_bounce");
        assertThat(bounced.getProviderEventAt()).isEqualTo(at);

        DeliveryRecord ok = sentEmail("<m2@smtp-relay.mailin.fr> · Brevo API 201");
        assertThat(dispatcher.applyEmailEvent("<m2@smtp-relay.mailin.fr>", "delivered", null, at)).isTrue();
        assertThat(ok.getStatus()).isEqualTo(DeliveryRecord.Status.DELIVERED);
        assertThat(ok.getDeliveredAt()).isEqualTo(at);
        // A late soft bounce of a delivered email changes nothing; a spam complaint is noted, the status stays.
        assertThat(dispatcher.applyEmailEvent("<m2@smtp-relay.mailin.fr>", "soft_bounce", "mailbox full", at)).isFalse();
        assertThat(dispatcher.applyEmailEvent("<m2@smtp-relay.mailin.fr>", "spam", null, at)).isTrue();
        assertThat(ok.getStatus()).isEqualTo(DeliveryRecord.Status.DELIVERED);
        assertThat(ok.getError()).isEqualTo("Marked as spam by the recipient");

        DeliveryRecord hard = sentEmail("<m3@relay>");
        assertThat(dispatcher.applyEmailEvent("<m3@relay>", "hard_bounce", "550 5.1.1 no such user", at)).isTrue();
        assertThat(hard.getStatus()).isEqualTo(DeliveryRecord.Status.FAILED);
        // "delivered" after a hard bounce is out of order: ignored.
        assertThat(dispatcher.applyEmailEvent("<m3@relay>", "delivered", null, at)).isFalse();
        assertThat(hard.getStatus()).isEqualTo(DeliveryRecord.Status.FAILED);

        DeliveryRecord opened = sentEmail("<m4@relay>");
        assertThat(dispatcher.applyEmailEvent("<m4@relay>", "opened", null, at)).isTrue();
        assertThat(opened.getOpenedAt()).isEqualTo(at);
        assertThat(opened.getStatus()).isEqualTo(DeliveryRecord.Status.DELIVERED);

        // Unknown events and ids change nothing.
        assertThat(dispatcher.applyEmailEvent("<m4@relay>", "request", null, at)).isFalse();
        assertThat(dispatcher.applyEmailEvent("<nobody@relay>", "delivered", null, at)).isFalse();
        assertThat(dispatcher.applyEmailEvent(null, "delivered", null, at)).isFalse();
    }

    @Test
    void aBrevoEventForAnEmailStillBeingSentIsIgnored() {
        DeliveryRecord q = sentEmail("<q@relay>");
        q.setStatus(DeliveryRecord.Status.QUEUED);
        assertThat(dispatcher.applyEmailEvent("<q@relay>", "delivered", null, null)).isFalse();
        assertThat(q.getStatus()).isEqualTo(DeliveryRecord.Status.QUEUED);
    }

    @Test
    void theMessageIdsWildcardsAreEscapedForTheLookup() {
        dispatcher.applyEmailEvent("<a_b%c!@relay>", "delivered", null, null);
        verify(records).findEmailByMessageId("<a_b%c!@relay>", "<a!_b!%c!!@relay> %");
    }

    @Test
    void hotelPhoneIsTheHotelsPhoneOrItsFrontDesk() {
        com.hospio.messaging.text.TextSender texts = mock(com.hospio.messaging.text.TextSender.class);
        when(texts.live(org.mockito.ArgumentMatchers.eq("WHATSAPP"), any())).thenReturn(true);
        when(texts.send(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any())).thenReturn(new com.hospio.messaging.text.TextSender.Outcome(true, "WHATSAPP", "wa-1", null));
        dispatcher.setTexts(texts);
        when(tenants.hotel("h-royal")).thenReturn(Optional.of(new TenantClient.Sender("Royal Suites Ikeja", "frontdesk@royal.example", "+234 803 000 0000")));
        dispatcher.accept(DeliveryRequestedEvent.builder().eventId("e-ph").businessId("b-royal").hotelId("h-royal").channel("WHATSAPP").to("+2348030000000")
                .text("@hotel: call @hotelPhone").kind("BOOKING_CONFIRMATION").template("t").params(java.util.List.of("@hotel", "@hotelPhone", "tok")).build());
        verify(texts).send("WHATSAPP", "+2348030000000", "Royal Suites Ikeja: call +234 803 000 0000", "t",
                java.util.List.of("Royal Suites Ikeja", "+234 803 000 0000", "tok"), false, null);
        dispatcher.accept(DeliveryRequestedEvent.builder().eventId("e-ph2").businessId("b-b").hotelId("h-b1").channel("WHATSAPP").to("+2348030000000")
                .text("Call @hotelPhone").kind("BOOKING_CONFIRMATION").template("t").params(java.util.List.of("@hotelPhone")).build());
        verify(texts).send("WHATSAPP", "+2348030000000", "Call its front desk number", "t", java.util.List.of("its front desk number"), false, null);
    }

    @Test
    void theWhatsAppBannerIsDrawnInTheHotelsColourOrIsHospio360s() {
        com.hospio.messaging.client.HotelBrandClient brands = mock(com.hospio.messaging.client.HotelBrandClient.class);
        dispatcher.setBrands(brands);
        when(brands.brand("h-royal")).thenReturn(EmailTemplates.Brand.of("#0B4F50", null));
        when(brands.brand("h-plain")).thenReturn(EmailTemplates.Brand.HOSPIO);
        assertThat(dispatcher.banner("h-royal", "Royal Suites & Spa")).isEqualTo("https://hospio360.com/whatsapp/banner?c=0b4f50&n=Royal%20Suites%20%26%20Spa");
        assertThat(dispatcher.banner("h-plain", "Plain Hotel")).isNull();
    }
}

package com.hospio.messaging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.integrations.settings.IntegrationTestResult;
import com.hospio.common.kafka.events.DeliveryRequestedEvent;
import com.hospio.messaging.entity.DeliveryRecord;
import com.hospio.messaging.mail.MailDispatcher;
import com.hospio.messaging.repository.DeliveryRecordRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Integrations → Brevo → Send a test email: one short email, recorded like any delivery; test addresses refused; once a minute. */
class TestEmailServiceTest {

    private final MailDispatcher mailer = mock(MailDispatcher.class);
    private final DeliveryRecordRepository deliveries = mock(DeliveryRecordRepository.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-18T13:00:00Z"));
    private TestEmailService service;

    @BeforeEach
    void setUp() {
        when(mailer.enabled()).thenReturn(true);
        when(mailer.accept(any())).thenAnswer(i -> DeliveryRecord.builder().id("d-1").channel("EMAIL")
                .toAddress(i.<DeliveryRequestedEvent>getArgument(0).getTo()).status(DeliveryRecord.Status.QUEUED).build());
        when(deliveries.findById("d-1")).thenReturn(Optional.of(DeliveryRecord.builder().id("d-1").channel("EMAIL").toAddress("t")
                .status(DeliveryRecord.Status.SENT).build()));
        service = new TestEmailService(mailer, deliveries, clock, Duration.ofSeconds(2));
    }

    @Test
    void sendsOneShortEmailRecordedLikeAnyDeliveryAndNeverEchoesTheAddress() {
        IntegrationTestResult r = service.send("  frontdesk@royalsuites.ng ");

        assertThat(r.ok()).isTrue();
        assertThat(r.message()).contains("Brevo accepted the test email").doesNotContain("royalsuites");
        ArgumentCaptor<DeliveryRequestedEvent> e = ArgumentCaptor.forClass(DeliveryRequestedEvent.class);
        verify(mailer).accept(e.capture());
        assertThat(e.getValue().getTo()).isEqualTo("frontdesk@royalsuites.ng");
        assertThat(e.getValue().getChannel()).isEqualTo("EMAIL");
        assertThat(e.getValue().getKind()).isEqualTo("TEST_EMAIL");
        assertThat(e.getValue().getSubject()).isEqualTo("Hospio360 test email — delivery tracking check");
        assertThat(e.getValue().getBusinessId()).isNull();
        assertThat(e.getValue().getEventId()).isNotBlank();
    }

    @Test
    void refusesTestAddressesAndNonAddressesWithASentence() {
        assertThat(service.send("qa@hospio-qa.test").message()).startsWith("Not sent: that is a test address");
        assertThat(service.send("someone@example.com").ok()).isFalse();
        assertThat(service.send("not an address").message()).startsWith("That isn't an email address");
        assertThat(service.send(" ").message()).isEqualTo("Type the address to send the test email to");
        verify(mailer, never()).accept(any());
    }

    @Test
    void atMostOneAMinute() {
        assertThat(service.send("a@royalsuites.ng").ok()).isTrue();
        clock.plus(Duration.ofSeconds(30));
        IntegrationTestResult again = service.send("a@royalsuites.ng");
        assertThat(again.ok()).isFalse();
        assertThat(again.message()).contains("less than a minute ago");
        clock.plus(Duration.ofSeconds(31));
        assertThat(service.send("a@royalsuites.ng").ok()).isTrue();
        verify(mailer, times(2)).accept(any());
    }

    @Test
    void anotherInstancesRecentTestEmailCountsToo() {
        when(deliveries.existsByKindAndCreatedAtAfter(eq("TEST_EMAIL"), any())).thenReturn(true);
        assertThat(service.send("a@royalsuites.ng").message()).contains("less than a minute ago");
        verify(mailer, never()).accept(any());
    }

    @Test
    void aFailureAndEmailSwitchedOffAreSaid() {
        when(deliveries.findById("d-1")).thenReturn(Optional.of(DeliveryRecord.builder().id("d-1").channel("EMAIL").toAddress("t")
                .status(DeliveryRecord.Status.FAILED).error("Brevo refused it: 401 Key not found").build()));
        assertThat(service.send("a@royalsuites.ng").message()).isEqualTo("The test email failed: Brevo refused it: 401 Key not found");

        when(mailer.enabled()).thenReturn(false);
        clock.plus(Duration.ofMinutes(2));
        assertThat(service.send("a@royalsuites.ng").message()).startsWith("Email is switched off");
    }

    /** A clock the test moves. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void plus(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

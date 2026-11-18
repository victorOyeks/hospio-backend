package com.hospio.messaging.service;

import com.hospio.common.integrations.settings.IntegrationTestResult;
import com.hospio.common.kafka.events.DeliveryRequestedEvent;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.messaging.entity.DeliveryRecord;
import com.hospio.messaging.mail.MailDispatcher;
import com.hospio.messaging.mail.TestAddresses;
import com.hospio.messaging.repository.DeliveryRecordRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Integrations → Brevo → "Send a test email" (SUPER_ADMIN, through platform-service; docs/integrations.md): one short plain email to an
 * address the admin types, sent and recorded like any delivery (kind {@code TEST_EMAIL}, no business), so Brevo's delivery report shows
 * in the connection checks. A test address is refused with a sentence; at most one per minute (across instances: the delivery log is
 * asked). The answer waits a few seconds for Brevo's acceptance. The address is never written to the answer.
 */
@Slf4j
@Service
public class TestEmailService {

    public static final String KIND = "TEST_EMAIL";
    public static final String SUBJECT = "Hospio360 test email — delivery tracking check";
    static final String TEXT = """
            This is a test email from the Hospio360 admin portal (Integrations → Brevo → Send a test email).

            It checks that email reaches a real inbox and that Brevo reports the delivery back to Hospio360. Nothing else is needed: \
            you can delete it.""";
    static final Duration EVERY = Duration.ofMinutes(1);

    private final MailDispatcher mailer;
    private final DeliveryRecordRepository deliveries;
    private final Clock clock;
    private final Duration wait;
    private Instant lastHere;

    @Autowired
    public TestEmailService(MailDispatcher mailer, DeliveryRecordRepository deliveries) {
        this(mailer, deliveries, Clock.systemUTC(), Duration.ofSeconds(10));
    }

    TestEmailService(MailDispatcher mailer, DeliveryRecordRepository deliveries, Clock clock, Duration wait) {
        this.mailer = mailer;
        this.deliveries = deliveries;
        this.clock = clock;
        this.wait = wait;
    }

    public synchronized IntegrationTestResult send(String to) {
        String address = to == null ? "" : to.trim();
        if (address.isEmpty()) {
            return IntegrationTestResult.failed("Type the address to send the test email to");
        }
        if (!MailDispatcher.address(address)) {
            return IntegrationTestResult.failed("That isn't an email address. Use one like name@yourdomain.com");
        }
        if (TestAddresses.reserved(address)) {
            return IntegrationTestResult.failed("Not sent: that is a test address (.test, .example, example.com…), which can never receive "
                    + "email. Use a real inbox you can open");
        }
        if (!mailer.enabled()) {
            return IntegrationTestResult.failed("Email is switched off (Integrations → SMTP → Email on), so nothing can be sent");
        }
        Instant now = clock.instant();
        boolean recent = lastHere != null && lastHere.isAfter(now.minus(EVERY))
                || Boolean.TRUE.equals(asSystem(() -> deliveries.existsByKindAndCreatedAtAfter(KIND, now.minus(EVERY))));
        if (recent) {
            return IntegrationTestResult.failed("A test email was sent less than a minute ago. Wait a minute, then try again");
        }
        lastHere = now;
        DeliveryRecord record = mailer.accept(DeliveryRequestedEvent.builder().eventId(UUID.randomUUID().toString()).occurredAt(now)
                .channel("EMAIL").to(address).subject(SUBJECT).text(TEXT).kind(KIND).ref("admin-test").build());
        if (record == null) {
            return IntegrationTestResult.failed("The test email couldn't be recorded, so it wasn't sent. Try again in a minute");
        }
        log.info("Admin test email recorded as delivery {}", record.getId());
        DeliveryRecord answer = awaitAnswer(record);
        return switch (answer.getStatus()) {
            case SENT, DELIVERED -> IntegrationTestResult.ok("Brevo accepted the test email. Its delivery shows in Connection checks → Email "
                    + "once Brevo reports it, usually within a minute");
            case FAILED -> IntegrationTestResult.failed("The test email failed: " + (answer.getError() == null ? "no reason given" : answer.getError()));
            case QUEUED -> IntegrationTestResult.ok("The test email is queued and goes out in a moment. Check Connection checks → Email in a minute");
            default -> IntegrationTestResult.failed("The test email wasn't sent: " + (answer.getError() != null ? answer.getError()
                    : answer.getStatus().name().toLowerCase().replace('_', ' ')));
        };
    }

    /** The record once the sender has answered, or as it is when {@code wait} runs out. */
    private DeliveryRecord awaitAnswer(DeliveryRecord record) {
        DeliveryRecord current = record;
        long until = System.nanoTime() + wait.toNanos();
        while (current.getStatus() == DeliveryRecord.Status.QUEUED && System.nanoTime() < until) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            String id = current.getId();
            Optional<DeliveryRecord> fresh = asSystem(() -> deliveries.findById(id));
            if (fresh.isEmpty()) {
                break;
            }
            current = fresh.get();
        }
        return current;
    }

    private static <T> T asSystem(Supplier<T> work) {
        Optional<Tenant> before = TenantContext.current();
        try {
            TenantContext.set(Tenant.system("test-email"));
            return work.get();
        } finally {
            if (before.isPresent()) {
                TenantContext.set(before.get());
            } else {
                TenantContext.clear();
            }
        }
    }
}

package com.hospio.payment.jobs;

import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.exception.HospioException;
import com.hospio.common.integrations.PosProvider;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.payment.entity.PaymentRequest;
import com.hospio.payment.repository.PaymentRequestRepository;
import com.hospio.payment.service.PaymentService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A card payment waits at the POS until the provider's webhook says approved or declined. Should a webhook be late or lost,
 * every 30 seconds the backend asks the provider itself about card payments still waiting that were sent 20 seconds to 30
 * minutes ago ({@link PaymentService#verify}), so the folio, the desk's screen (which only ever asks our backend, every 4
 * seconds) and My payments catch up without anyone pressing anything. The development "logging" provider has nothing to
 * ask, so the job does nothing there. One instance runs it at a time (SingleInstanceScheduling).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hospio.worker.enabled", havingValue = "true", matchIfMissing = true)
public class PendingPaymentCheckJob {

    static final Duration SETTLE = Duration.ofSeconds(20);
    static final Duration GIVE_UP = Duration.ofMinutes(30);
    static final List<PaymentStatus> WAITING = List.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING);

    private final PaymentRequestRepository payments;
    private final PaymentService service;
    private final PosProvider provider;
    /** Now, a test seam. */
    Clock clock = Clock.systemUTC();

    public PendingPaymentCheckJob(PaymentRequestRepository payments, PaymentService service, PosProvider provider) {
        this.payments = payments;
        this.service = service;
        this.provider = provider;
    }

    @Scheduled(fixedDelayString = "${hospio.payments.pending-check-ms:30000}", initialDelayString = "${hospio.payments.pending-check-ms:30000}")
    public void run() {
        if ("logging".equals(provider.name())) {
            return;
        }
        check();
    }

    /** Asks the provider about each waiting card payment; answers how many it settled (approved or declined). */
    int check() {
        Instant now = Instant.now(clock);
        List<PaymentRequest> waiting = TenantContext.runWith(Tenant.system("pending-payment-check"), () ->
                payments.findTop200ByMethodAndStatusInAndProviderRefIsNotNullAndCreatedAtBetweenOrderByCreatedAtAsc(
                        PaymentMethod.CARD, WAITING, now.minus(GIVE_UP), now.minus(SETTLE)));
        int settled = 0;
        for (PaymentRequest p : waiting) {
            try {
                PaymentStatus after = TenantContext.runWith(
                        Tenant.systemFor(p.getBusinessId(), p.getHotelId(), null, "Pending payment check"),
                        () -> service.verify(p.getId()).status());
                if (after != p.getStatus()) {
                    settled++;
                    log.info("Payment {} settled by the pending check: {}", p.getId(), after);
                }
            } catch (HospioException e) {
                // "Still pending. The POS hasn't confirmed it yet": asked again on the next run.
            } catch (RuntimeException e) {
                log.warn("Pending check for payment {} failed: {}", p.getId(), e.getMessage());
            }
        }
        return settled;
    }
}

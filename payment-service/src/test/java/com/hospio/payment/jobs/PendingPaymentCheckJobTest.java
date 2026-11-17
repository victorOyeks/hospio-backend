package com.hospio.payment.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.integrations.LoggingPosProvider;
import com.hospio.common.integrations.MoniepointPosProvider;
import com.hospio.common.integrations.PosNotConnectedException;
import com.hospio.payment.dto.PaymentResponse;
import com.hospio.payment.entity.PaymentRequest;
import com.hospio.payment.repository.PaymentRequestRepository;
import com.hospio.payment.service.PaymentService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A late or lost POS webhook is caught up by the backend; the desk's screen only ever asks our backend. */
class PendingPaymentCheckJobTest {

    private final PaymentRequestRepository payments = mock(PaymentRequestRepository.class);
    private final PaymentService service = mock(PaymentService.class);
    private static final Instant NOW = Instant.parse("2027-01-10T12:00:00Z");

    private static PaymentRequest waiting(String id) {
        PaymentRequest p = new PaymentRequest();
        p.setId(id);
        p.setHotelId("h1");
        p.setBusinessId("b1");
        p.setStatus(PaymentStatus.PROCESSING);
        p.setMethod(PaymentMethod.CARD);
        p.setAmount(new BigDecimal("35000"));
        return p;
    }

    private static PaymentResponse answered(PaymentStatus status) {
        PaymentResponse r = mock(PaymentResponse.class);
        when(r.status()).thenReturn(status);
        return r;
    }

    @Test
    void waitingCardPaymentsAreAskedAboutAndStillPendingOnesWait() {
        PendingPaymentCheckJob job = new PendingPaymentCheckJob(payments, service, new MoniepointPosProvider(), org.mockito.Mockito.mock(com.hospio.payment.pos.HotelPosRouter.class));
        job.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        when(payments.findTop200ByMethodInAndStatusInAndProviderRefIsNotNullAndCreatedAtBetweenOrderByCreatedAtAsc(
                eq(PendingPaymentCheckJob.POS_METHODS), eq(PendingPaymentCheckJob.ASKED), eq(NOW.minus(PendingPaymentCheckJob.GIVE_UP)),
                eq(NOW.minus(PendingPaymentCheckJob.SETTLE)))).thenReturn(List.of(waiting("p1"), waiting("p2")));
        PaymentResponse approved = answered(PaymentStatus.APPROVED);
        when(service.verify("p1")).thenReturn(approved);
        when(service.verify("p2")).thenThrow(new BadRequestException("Still pending. The POS hasn't confirmed it yet"));
        assertThat(job.check()).isEqualTo(1);
    }

    @Test
    void theDevelopmentProviderHasNothingToAskButItsPaymentsStillTimeOut() {
        PendingPaymentCheckJob job = new PendingPaymentCheckJob(payments, service, new LoggingPosProvider(), org.mockito.Mockito.mock(com.hospio.payment.pos.HotelPosRouter.class));
        job.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        when(payments.findTop200ByMethodInAndStatusInAndCreatedAtBeforeOrderByCreatedAtAsc(PendingPaymentCheckJob.POS_METHODS,
                PendingPaymentCheckJob.WAITING, NOW.minus(java.time.Duration.ofMinutes(5)))).thenReturn(List.of(waiting("p1"), waiting("p2")));
        when(service.timeOut("p1", java.time.Duration.ofMinutes(5))).thenReturn(true);
        when(service.timeOut("p2", java.time.Duration.ofMinutes(5))).thenReturn(false); // answered meanwhile

        job.run();

        verify(payments, never()).findTop200ByMethodInAndStatusInAndProviderRefIsNotNullAndCreatedAtBetweenOrderByCreatedAtAsc(any(), any(), any(), any());
        verify(service).timeOut("p1", java.time.Duration.ofMinutes(5));
        assertThat(job.timeOut()).isEqualTo(1);
    }

    @Test
    void aPlaceholderProviderRefusesClearly() {
        try {
            new MoniepointPosProvider().requestPayment("T1", "p1", new BigDecimal("1000"), "NGN", "x");
        } catch (PosNotConnectedException e) {
            assertThat(e.getMessage()).isEqualTo("Moniepoint POS is not connected yet. Take cash or a bank transfer for now.");
            return;
        }
        throw new AssertionError("expected PosNotConnectedException");
    }
}

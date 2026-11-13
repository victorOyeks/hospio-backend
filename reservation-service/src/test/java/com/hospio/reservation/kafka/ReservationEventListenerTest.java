package com.hospio.reservation.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.kafka.events.PaymentApprovedEvent;
import com.hospio.common.kafka.events.RefundApprovedEvent;
import com.hospio.common.tenancy.TenantBinder;
import com.hospio.reservation.repository.BookingRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Amount paid moves in one statement, once per payment or refund: a redelivered event changes nothing. */
class ReservationEventListenerTest {

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final ReservationEventListener listener = new ReservationEventListener(bookings, mock(TenantBinder.class));

    private static PaymentApprovedEvent payment(String id) {
        return PaymentApprovedEvent.builder().paymentId(id).bookingId("b1").amount(new BigDecimal("20000")).build();
    }

    @Test
    void aPaymentIsAddedOnceHoweverOftenItIsDelivered() {
        when(bookings.recordPayment("payment:p1", "b1", new BigDecimal("20000"))).thenReturn(1, 0);
        when(bookings.existsById("b1")).thenReturn(true);
        when(bookings.addPaid(eq("b1"), any(), any())).thenReturn(1);

        listener.onPaymentApproved(payment("p1"));
        listener.onPaymentApproved(payment("p1"));

        verify(bookings, times(1)).addPaid(eq("b1"), eq(new BigDecimal("20000")), any());
        verify(bookings, never()).save(any());
        verify(bookings, never()).findById(anyString());
    }

    @Test
    void aRefundTakesFromWhatWasPaidOnce() {
        when(bookings.recordPayment("refund:rf1", "b1", new BigDecimal("-5000"))).thenReturn(1, 0);
        when(bookings.existsById("b1")).thenReturn(true);
        RefundApprovedEvent e = RefundApprovedEvent.builder().refundId("rf1").paymentId("p1").bookingId("b1").amount(new BigDecimal("5000")).build();

        listener.onRefundApproved(e);
        listener.onRefundApproved(e);

        verify(bookings, times(1)).addPaid(eq("b1"), eq(new BigDecimal("-5000")), any());
    }

    @Test
    void aPaymentForABookingThatDoesNotExistChangesNothing() {
        when(bookings.recordPayment(anyString(), anyString(), any())).thenReturn(0);
        when(bookings.existsById("b1")).thenReturn(false);

        listener.onPaymentApproved(payment("p2"));

        verify(bookings, never()).addPaid(anyString(), any(), any());
    }
}

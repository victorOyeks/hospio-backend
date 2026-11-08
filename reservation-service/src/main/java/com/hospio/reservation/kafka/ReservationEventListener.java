package com.hospio.reservation.kafka;

import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.PaymentApprovedEvent;
import com.hospio.common.kafka.events.RefundApprovedEvent;
import com.hospio.common.tenancy.TenantBinder;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.reservation.entity.Booking;
import com.hospio.reservation.repository.BookingRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps each booking's {@code amountPaid} current from payment-service: an approved payment against the
 * booking adds to it, an approved refund takes from it. That is what lets the desk see a deposit taken
 * at booking time before the folio exists, and what "due now" is computed from.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationEventListener {

    private final BookingRepository bookings;
    private final TenantBinder tenant;

    @Transactional
    @KafkaListener(topics = KafkaTopics.PAYMENT_APPROVED)
    public void onPaymentApproved(PaymentApprovedEvent e) {
        if (e.getBookingId() == null || e.getAmount() == null) {
            return;
        }
        tenant.bind(e);
        try {
            bookings.findById(e.getBookingId()).ifPresentOrElse(b -> add(b, e.getAmount()),
                    () -> log.warn("PaymentApproved {} names booking {} which does not exist", e.getPaymentId(), e.getBookingId()));
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.REFUND_APPROVED)
    public void onRefundApproved(RefundApprovedEvent e) {
        if (e.getBookingId() == null || e.getAmount() == null) {
            return;
        }
        tenant.bind(e);
        try {
            bookings.findById(e.getBookingId()).ifPresent(b -> add(b, e.getAmount().negate()));
        } finally {
            TenantContext.clear();
        }
    }

    private void add(Booking b, BigDecimal delta) {
        BigDecimal current = b.getAmountPaid() == null ? BigDecimal.ZERO : b.getAmountPaid();
        b.setAmountPaid(current.add(delta).max(BigDecimal.ZERO));
        bookings.save(b);
    }
}

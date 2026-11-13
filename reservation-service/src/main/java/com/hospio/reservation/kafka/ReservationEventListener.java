package com.hospio.reservation.kafka;

import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.PaymentApprovedEvent;
import com.hospio.common.kafka.events.RefundApprovedEvent;
import com.hospio.common.tenancy.TenantBinder;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.reservation.repository.BookingRepository;
import java.math.BigDecimal;
import java.time.Instant;
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
            if (!add(e.getBookingId(), e.getPaymentId() == null ? null : "payment:" + e.getPaymentId(), e.getAmount())) {
                log.warn("PaymentApproved {} names booking {} which does not exist", e.getPaymentId(), e.getBookingId());
            }
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
            add(e.getBookingId(), e.getRefundId() == null ? null : "refund:" + e.getRefundId(), e.getAmount().negate());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Adds {@code delta} to the booking's amount paid in one statement (never below zero), so it can't overwrite a check-in or
     * cancellation saved meanwhile. With a {@code ref} the payment or refund is recorded in booking_payments in the same transaction and
     * applied only the first time: a redelivered event changes nothing. False when the booking does not exist.
     */
    boolean add(String bookingId, String ref, BigDecimal delta) {
        if (ref != null && bookings.recordPayment(ref, bookingId, delta) == 0) {
            if (!bookings.existsById(bookingId)) {
                return false;
            }
            log.info("{} was already applied to booking {}; ignoring the repeat", ref, bookingId);
            return true;
        }
        return bookings.addPaid(bookingId, delta, Instant.now()) > 0;
    }
}

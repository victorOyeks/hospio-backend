package com.hospio.reservation.dto;

import com.hospio.common.enums.BookingStatus;
import com.hospio.common.enums.PaymentPlan;
import com.hospio.common.enums.PriceKind;
import com.hospio.reservation.entity.Booking;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record BookingResponse(String id, String reference, String hotelId, String guestId, String guestName, String roomTypeId,
                              String roomTypeName, String roomId, String roomNumber, LocalDate checkInDate, LocalDate checkOutDate,
                              int nights, int adults, int children, BookingStatus status, PriceKind priceKind, BigDecimal nightlyRate,
                              BigDecimal totalAmount, BigDecimal amountPaid, PaymentPlan paymentPlan, BigDecimal amountDueNow, String organizationId,
                              String source, String notes, Instant checkedInAt, Instant checkedOutAt, LocalTime lateCheckoutUntil,
                              Instant cancelledAt, String cancellationReason, Instant createdAt, String warning,
                              java.util.List<com.hospio.common.delivery.Delivery> deliveries) {

    public static BookingResponse of(Booking b) {
        BigDecimal paid = b.getAmountPaid() == null ? BigDecimal.ZERO : b.getAmountPaid();
        BigDecimal planned = switch (b.getPaymentPlan()) {
            case PAY_IN_FULL -> b.getTotalAmount();
            case DEPOSIT_50 -> b.getTotalAmount().divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            case PAY_AT_CHECKOUT, ORGANIZATION -> BigDecimal.ZERO;
        };
        BigDecimal dueNow = planned.subtract(paid).max(BigDecimal.ZERO);
        return new BookingResponse(b.getId(), b.getReference(), b.getHotelId(), b.getGuestId(), b.getGuestName(), b.getRoomTypeId(),
                b.getRoomTypeName(), b.getRoomId(), b.getRoomNumber(), b.getCheckInDate(), b.getCheckOutDate(), b.nights(), b.getAdults(),
                b.getChildren(), b.getStatus(), b.getPriceKind(), b.getNightlyRate(), b.getTotalAmount(), paid, b.getPaymentPlan(), dueNow,
                b.getOrganizationId(), b.getSource(), b.getNotes(), b.getCheckedInAt(), b.getCheckedOutAt(), b.getLateCheckoutUntil(),
                b.getCancelledAt(), b.getCancellationReason(), b.getCreatedAt(), null, null);
    }

    public BookingResponse withWarning(String warning) {
        return new BookingResponse(id, reference, hotelId, guestId, guestName, roomTypeId, roomTypeName, roomId, roomNumber, checkInDate,
                checkOutDate, nights, adults, children, status, priceKind, nightlyRate, totalAmount, amountPaid, paymentPlan, amountDueNow,
                organizationId, source, notes, checkedInAt, checkedOutAt, lateCheckoutUntil, cancelledAt, cancellationReason, createdAt, warning, deliveries);
    }

    /** What the guest was sent about this change (echoed on staging; empty in production). */
    public BookingResponse withDeliveries(java.util.List<com.hospio.common.delivery.Delivery> sent) {
        return new BookingResponse(id, reference, hotelId, guestId, guestName, roomTypeId, roomTypeName, roomId, roomNumber, checkInDate,
                checkOutDate, nights, adults, children, status, priceKind, nightlyRate, totalAmount, amountPaid, paymentPlan, amountDueNow,
                organizationId, source, notes, checkedInAt, checkedOutAt, lateCheckoutUntil, cancelledAt, cancellationReason, createdAt, warning, sent);
    }
}

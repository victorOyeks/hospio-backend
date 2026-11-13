package com.hospio.reservation.entity;

import com.hospio.common.entity.TenantEntity;
import com.hospio.common.enums.BookingStatus;
import com.hospio.common.enums.PaymentPlan;
import com.hospio.common.enums.PriceKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A stay: booked, checked in, checked out or cancelled. Reservations alone owns availability. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "bookings")
public class Booking extends TenantEntity {

    @Column(nullable = false, unique = true, length = 12)
    private String reference;

    @Column(name = "guest_id", nullable = false, length = 36)
    private String guestId;

    @Column(name = "guest_name", nullable = false, length = 150)
    private String guestName;

    @Column(name = "room_type_id", nullable = false, length = 36)
    private String roomTypeId;

    @Column(name = "room_type_name", length = 100)
    private String roomTypeName;

    @Column(name = "room_id", length = 36)
    private String roomId;

    @Column(name = "room_number", length = 20)
    private String roomNumber;

    @Column(name = "check_in_date", nullable = false)
    private LocalDate checkInDate;

    @Column(name = "check_out_date", nullable = false)
    private LocalDate checkOutDate;

    @Column(nullable = false)
    @lombok.Builder.Default
    private int adults = 1;

    @Column(nullable = false)
    @lombok.Builder.Default
    private int children = 0;

    /** When the guest expects to arrive, "15:30" (none for a walk-in checked in on the spot). */
    @Column(name = "expected_arrival", length = 5)
    private String expectedArrival;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @lombok.Builder.Default
    private BookingStatus status = BookingStatus.BOOKED;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_kind", nullable = false)
    @lombok.Builder.Default
    private PriceKind priceKind = PriceKind.NORMAL;

    @Column(name = "nightly_rate", nullable = false)
    private BigDecimal nightlyRate;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    /** Approved payments minus approved refunds against this stay, kept current by payment events. */
    @Column(name = "amount_paid", nullable = false)
    @lombok.Builder.Default
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_plan", nullable = false)
    @lombok.Builder.Default
    private PaymentPlan paymentPlan = PaymentPlan.PAY_IN_FULL;

    @Column(name = "organization_id", length = 36)
    private String organizationId;

    @Column(length = 40)
    private String source;

    @Column(length = 1000)
    private String notes;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "checked_in_at")
    private Instant checkedInAt;

    @Column(name = "checked_out_at")
    private Instant checkedOutAt;

    @Column(name = "late_checkout_until")
    private LocalTime lateCheckoutUntil;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", length = 300)
    private String cancellationReason;

    public int nights() {
        return (int) Math.max(1, ChronoUnit.DAYS.between(checkInDate, checkOutDate));
    }

    public boolean isActive() {
        return status == BookingStatus.BOOKED || status == BookingStatus.CHECKED_IN;
    }
}

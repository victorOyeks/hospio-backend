package com.hospio.reservation.dto;

import com.hospio.common.enums.PaymentPlan;
import com.hospio.common.enums.PriceKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * New booking. Either an existing guest or the guest's details; a room type and optionally a room;
 * the price kind (special and complimentary are manager-only) and how the guest will pay. {@code walkIn}: the guest is checked in to
 * {@code roomId} straight after, so a room somebody is still checked in to is refused now rather than at check-in.
 */
public record CreateBookingRequest(String guestId, GuestRequest guest, @NotBlank String roomTypeId, String roomId,
                                   @NotNull LocalDate checkInDate, @NotNull LocalDate checkOutDate,
                                   Integer adults, Integer children, PriceKind priceKind, BigDecimal specialRate,
                                   PaymentPlan paymentPlan, String organizationId, String source, String notes,
                                   Boolean walkIn, String expectedArrival) {
}

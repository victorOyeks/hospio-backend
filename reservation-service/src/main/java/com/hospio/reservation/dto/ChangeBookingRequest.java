package com.hospio.reservation.dto;

import java.time.LocalDate;

/** Change dates or room. Any field left null keeps its current value. */
public record ChangeBookingRequest(LocalDate checkInDate, LocalDate checkOutDate, String roomId, String roomTypeId, String reason) {
}

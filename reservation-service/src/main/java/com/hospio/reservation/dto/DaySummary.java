package com.hospio.reservation.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * One business day at the hotel, for finance's night audit and today screen. {@code arrivals} counts everyone expected that
 * day who was not cancelled; {@code arrivalsPending} are the ones still BOOKED (not in, not no-show), including stale earlier
 * dates. {@code departingToday} is everyone whose check-out date is the day, whether or not they have left yet. {@code departuresPending} are guests still CHECKED_IN whose check-out date has come.
 */
public record DaySummary(LocalDate date, int arrivals, int arrivalsPending, int departures, int departuresPending, int inHouse, int noShows,
                         int roomsOccupied, List<Stay> pendingArrivals, List<Stay> pendingDepartures, List<Stay> departingToday) {

    public record Stay(String bookingId, String reference, String guestName, String roomNumber, LocalDate checkInDate, LocalDate checkOutDate) {
    }
}

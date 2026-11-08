package com.hospio.reservation.dto;

/** The admin portal's hotel card: occupancy today, arrivals, departures, who is in house, bookings made in the last 7 days. */
public record HotelOverviewResponse(int occupancyPct, long arrivalsToday, long departuresToday, long inHouse, long bookingsLast7Days) {
}

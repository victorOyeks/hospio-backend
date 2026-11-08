package com.hospio.reservation.dto;

import java.util.List;

/** Front office 1.1 · Today at the desk: arrivals, departures and who is in house. */
public record TodayResponse(List<BookingResponse> arrivals, List<BookingResponse> departures, List<BookingResponse> inHouse) {
}

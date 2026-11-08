package com.hospio.property.dto;

/** The admin portal's hotel card: rooms, how many are out of order, and active room types. */
public record HotelOverviewResponse(long rooms, long outOfOrder, long roomTypes) {
}

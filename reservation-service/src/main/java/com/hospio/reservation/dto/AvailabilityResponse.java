package com.hospio.reservation.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Availability of a room type for [from, to). {@code freeRoomIds}: nobody holds the room on those nights and nobody is in it.
 * {@code turnoverRoomIds}: a guest due out today is still checked in; the room can be given to an arrival, who is checked in once
 * that guest is checked out. {@code available} counts the type's rooms not held on those nights (turnover rooms included).
 */
public record AvailabilityResponse(String roomTypeId, String roomTypeName, int roomsOfType, long booked, long available,
                                   BigDecimal nightlyRate, List<String> freeRoomIds, List<String> turnoverRoomIds) {
}

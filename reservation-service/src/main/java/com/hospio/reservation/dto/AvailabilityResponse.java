package com.hospio.reservation.dto;

import java.math.BigDecimal;
import java.util.List;

public record AvailabilityResponse(String roomTypeId, String roomTypeName, int roomsOfType, long booked, long available,
                                   BigDecimal nightlyRate, List<String> freeRoomIds) {
}

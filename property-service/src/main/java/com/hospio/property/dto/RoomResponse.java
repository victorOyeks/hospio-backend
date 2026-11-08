package com.hospio.property.dto;

import com.hospio.property.entity.Room;
import java.math.BigDecimal;
import java.time.LocalDate;

public record RoomResponse(String id, String hotelId, String number, String floor, String areaId, String roomTypeId, String roomTypeName,
                           BigDecimal basePrice, int capacityAdults, int capacityChildren, Room.Status status, String outOfOrderReason,
                           LocalDate outOfOrderUntil, String notes) {
}

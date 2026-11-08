package com.hospio.property.dto;

import com.hospio.property.entity.SpecialPrice;
import java.math.BigDecimal;
import java.util.List;

public record RoomTypeResponse(String id, String hotelId, String name, String description, BigDecimal basePrice, int capacityAdults,
                               int capacityChildren, String facilities, boolean active, long roomCount, List<SpecialPrice> specialPrices) {
}

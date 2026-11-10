package com.hospio.property.dto;

import com.hospio.property.entity.SpecialPrice;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A room type. {@code requestedBasePrice} is a manager's new base price waiting for the owner (with who asked and when);
 * {@code priceSentToOwner} is true on the save that sent it.
 */
public record RoomTypeResponse(String id, String hotelId, String name, String description, BigDecimal basePrice, int capacityAdults,
                               int capacityChildren, String facilities, boolean active, long roomCount, List<SpecialPrice> specialPrices,
                               BigDecimal requestedBasePrice, String priceRequestedByName, Instant priceRequestedAt, boolean priceSentToOwner) {
}

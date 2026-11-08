package com.hospio.property.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record RoomTypeRequest(@NotBlank String name, String description, @NotNull BigDecimal basePrice, Integer capacityAdults,
                              Integer capacityChildren, String facilities, Boolean active) {
}

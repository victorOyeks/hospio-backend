package com.hospio.property.dto;

import jakarta.validation.constraints.NotBlank;

public record RoomRequest(@NotBlank String number, @NotBlank String roomTypeId, String areaId, String floor, String notes) {
}

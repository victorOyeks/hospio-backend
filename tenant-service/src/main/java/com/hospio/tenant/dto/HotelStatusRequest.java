package com.hospio.tenant.dto;

import com.hospio.tenant.entity.Hotel;
import jakarta.validation.constraints.NotNull;

public record HotelStatusRequest(@NotNull Hotel.Status status, String reason) {
}

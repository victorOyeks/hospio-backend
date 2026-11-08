package com.hospio.reservation.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelBookingRequest(@NotBlank String reason, Boolean refundable) {
}

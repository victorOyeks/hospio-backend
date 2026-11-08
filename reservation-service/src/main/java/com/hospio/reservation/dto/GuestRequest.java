package com.hospio.reservation.dto;

import jakarta.validation.constraints.NotBlank;

public record GuestRequest(@NotBlank String fullName, String phone, String email, String idType, String idNumber, String nationality,
                           String preferences, String notes) {
}

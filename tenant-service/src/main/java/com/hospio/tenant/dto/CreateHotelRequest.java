package com.hospio.tenant.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateHotelRequest(@NotBlank String name, String city, String address, String phone, String email, String timezone,
                                 String businessId) {
}

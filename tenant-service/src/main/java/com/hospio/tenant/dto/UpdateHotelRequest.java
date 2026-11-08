package com.hospio.tenant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Hotel profile edit; null fields are left as they are. */
public record UpdateHotelRequest(@NotBlank String name, String city, String address, String phone, String email, String timezone,
                                 @Pattern(regexp = "\\d{2}:\\d{2}") String checkInTime, @Pattern(regexp = "\\d{2}:\\d{2}") String checkOutTime,
                                 @Min(1) @Max(5) Integer starRating, String managerUserId) {
}

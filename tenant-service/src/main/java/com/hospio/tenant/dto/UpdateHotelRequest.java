package com.hospio.tenant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Hotel profile edit; null fields are left as they are. */
public record UpdateHotelRequest(String name, String city, String address, String phone, String email, String timezone,
                                 String checkInTime, String checkOutTime,
                                 @Min(1) @Max(5) Integer starRating, String managerUserId) {
}

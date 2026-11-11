package com.hospio.tenant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** A new hotel. Owners add to their own business; platform callers say which with {@code businessId}. */
public record CreateHotelRequest(String name, String city, String address, String phone, String email, String timezone,
                                 String checkInTime, String checkOutTime,
                                 @Min(1) @Max(5) Integer starRating, String managerUserId, String businessId) {
}

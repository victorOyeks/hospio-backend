package com.hospio.tenant.dto;

import com.hospio.common.venue.VenueType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * A new location. Owners add to their own business; platform callers say which with {@code businessId}. {@code venueType} left out
 * takes the business's kind (a lounge business's new location is a lounge).
 */
public record CreateHotelRequest(String name, String city, String address, String phone, String email, String timezone,
                                 String checkInTime, String checkOutTime,
                                 @Min(1) @Max(5) Integer starRating, String managerUserId, String businessId, VenueType venueType) {

    public CreateHotelRequest(String name, String city, String address, String phone, String email, String timezone,
                              String checkInTime, String checkOutTime, Integer starRating, String managerUserId, String businessId) {
        this(name, city, address, phone, email, timezone, checkInTime, checkOutTime, starRating, managerUserId, businessId, null);
    }
}

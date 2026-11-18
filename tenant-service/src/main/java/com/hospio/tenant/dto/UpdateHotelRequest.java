package com.hospio.tenant.dto;

import com.hospio.common.venue.VenueType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Location profile edit; null fields are left as they are. */
public record UpdateHotelRequest(String name, String city, String address, String phone, String email, String timezone,
                                 String checkInTime, String checkOutTime,
                                 @Min(1) @Max(5) Integer starRating, String managerUserId, VenueType venueType) {
}

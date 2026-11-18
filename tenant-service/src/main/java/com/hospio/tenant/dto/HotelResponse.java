package com.hospio.tenant.dto;

import com.hospio.common.venue.VenueType;
import com.hospio.tenant.entity.Hotel;
import java.time.Instant;

/**
 * A location. {@code venueType} is what kind of place it is; {@code hasAccommodation} (a hotel, or any location with rooms) is what
 * the apps and the other services gate rooms, bookings, folios and charge-to-room on (docs/plan-venue-type.md).
 */
public record HotelResponse(String id, String businessId, String name, String city, String address, String phone, String email,
                            String timezone, Hotel.Status status, String statusReason, int roomCount, String checkInTime,
                            String checkOutTime, Integer starRating, String managerUserId, Instant createdAt,
                            VenueType venueType, boolean hasAccommodation) {

    /** A hotel as before venue types (tests and older callers). */
    public HotelResponse(String id, String businessId, String name, String city, String address, String phone, String email,
                         String timezone, Hotel.Status status, String statusReason, int roomCount, String checkInTime,
                         String checkOutTime, Integer starRating, String managerUserId, Instant createdAt) {
        this(id, businessId, name, city, address, phone, email, timezone, status, statusReason, roomCount, checkInTime, checkOutTime,
                starRating, managerUserId, createdAt, VenueType.HOTEL, true);
    }

    public static HotelResponse of(Hotel h) {
        return new HotelResponse(h.getId(), h.getBusinessId(), h.getName(), h.getCity(), h.getAddress(), h.getPhone(), h.getEmail(),
                h.getTimezone(), h.getStatus(), h.getStatusReason(), h.getRoomCount(), h.getCheckInTime(), h.getCheckOutTime(),
                h.getStarRating(), h.getManagerUserId(), h.getCreatedAt(), h.getVenueType(), h.hasAccommodation());
    }
}

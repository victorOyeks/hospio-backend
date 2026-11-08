package com.hospio.tenant.dto;

import com.hospio.tenant.entity.Hotel;
import java.time.Instant;

public record HotelResponse(String id, String businessId, String name, String city, String address, String phone, String email,
                            String timezone, Hotel.Status status, String statusReason, int roomCount, String checkInTime,
                            String checkOutTime, Integer starRating, String managerUserId, Instant createdAt) {
    public static HotelResponse of(Hotel h) {
        return new HotelResponse(h.getId(), h.getBusinessId(), h.getName(), h.getCity(), h.getAddress(), h.getPhone(), h.getEmail(),
                h.getTimezone(), h.getStatus(), h.getStatusReason(), h.getRoomCount(), h.getCheckInTime(), h.getCheckOutTime(),
                h.getStarRating(), h.getManagerUserId(), h.getCreatedAt());
    }
}

package com.hospio.tenant.dto;

import com.hospio.tenant.entity.Hotel;

public record HotelResponse(String id, String businessId, String name, String city, String address, String phone, String email,
                            String timezone, Hotel.Status status, int roomCount) {
    public static HotelResponse of(Hotel h) {
        return new HotelResponse(h.getId(), h.getBusinessId(), h.getName(), h.getCity(), h.getAddress(), h.getPhone(), h.getEmail(),
                h.getTimezone(), h.getStatus(), h.getRoomCount());
    }
}

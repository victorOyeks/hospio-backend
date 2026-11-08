package com.hospio.property.service;

import com.hospio.property.dto.HotelOverviewResponse;
import com.hospio.property.entity.Room;
import com.hospio.property.repository.RoomRepository;
import com.hospio.property.repository.RoomTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only figures for the platform admin portal; the caller is the platform, so every hotel is visible. */
@Service
@RequiredArgsConstructor
public class AdminOverviewService {

    private final RoomRepository rooms;
    private final RoomTypeRepository roomTypes;

    @Transactional(readOnly = true)
    public HotelOverviewResponse hotelOverview(String hotelId) {
        return new HotelOverviewResponse(rooms.countByHotelId(hotelId), rooms.countByHotelIdAndStatus(hotelId, Room.Status.OUT_OF_ORDER),
                roomTypes.countByHotelIdAndActiveTrue(hotelId));
    }
}

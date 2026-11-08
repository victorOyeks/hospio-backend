package com.hospio.property.repository;

import com.hospio.property.entity.Room;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, String> {
    List<Room> findByHotelIdOrderByNumber(String hotelId);

    List<Room> findByHotelIdAndRoomTypeIdOrderByNumber(String hotelId, String roomTypeId);

    Optional<Room> findByHotelIdAndNumber(String hotelId, String number);

    long countByHotelId(String hotelId);

    long countByHotelIdAndStatus(String hotelId, Room.Status status);

    boolean existsByRoomTypeId(String roomTypeId);
}

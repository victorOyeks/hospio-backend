package com.hospio.housekeeping.repository;

import com.hospio.housekeeping.entity.RoomStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomStatusRepository extends JpaRepository<RoomStatus, String> {
    Optional<RoomStatus> findByRoomId(String roomId);

    List<RoomStatus> findByHotelIdOrderByRoomNumber(String hotelId);
}

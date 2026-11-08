package com.hospio.housekeeping.repository;

import com.hospio.housekeeping.entity.LinenMovement;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinenMovementRepository extends JpaRepository<LinenMovement, String> {
    List<LinenMovement> findByHotelIdOrderByCreatedAtDesc(String hotelId);
}

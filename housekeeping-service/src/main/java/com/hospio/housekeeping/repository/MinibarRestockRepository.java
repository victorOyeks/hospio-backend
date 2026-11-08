package com.hospio.housekeeping.repository;

import com.hospio.housekeeping.entity.MinibarRestock;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MinibarRestockRepository extends JpaRepository<MinibarRestock, String> {
    List<MinibarRestock> findByHotelIdOrderByCreatedAtDesc(String hotelId);
}

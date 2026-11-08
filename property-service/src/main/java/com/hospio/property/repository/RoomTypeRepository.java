package com.hospio.property.repository;

import com.hospio.property.entity.RoomType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomTypeRepository extends JpaRepository<RoomType, String> {
    List<RoomType> findByHotelIdOrderByName(String hotelId);

    long countByHotelIdAndActiveTrue(String hotelId);
}

package com.hospio.property.repository;

import com.hospio.property.entity.Area;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AreaRepository extends JpaRepository<Area, String> {
    List<Area> findByHotelIdOrderBySortOrderAscNameAsc(String hotelId);
}

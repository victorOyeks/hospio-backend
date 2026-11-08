package com.hospio.property.repository;

import com.hospio.property.entity.SpecialPrice;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpecialPriceRepository extends JpaRepository<SpecialPrice, String> {
    List<SpecialPrice> findByRoomTypeIdOrderByFromDate(String roomTypeId);
}

package com.hospio.housekeeping.repository;

import com.hospio.housekeeping.entity.LaundryPrice;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LaundryPriceRepository extends JpaRepository<LaundryPrice, String> {
    List<LaundryPrice> findByHotelIdOrderBySortOrderAscNameAsc(String hotelId);
}

package com.hospio.inventory.repository;

import com.hospio.inventory.entity.StockItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockItemRepository extends JpaRepository<StockItem, String> {
    List<StockItem> findByHotelIdAndActiveTrueOrderByCategoryAscNameAsc(String hotelId);
}

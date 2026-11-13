package com.hospio.inventory.repository;

import com.hospio.inventory.entity.StockLevel;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockLevelRepository extends JpaRepository<StockLevel, String>, StockLevelLocking {
    Optional<StockLevel> findByItemIdAndStoreId(String itemId, String storeId);

    List<StockLevel> findByStoreId(String storeId);

    List<StockLevel> findByHotelId(String hotelId);
    List<StockLevel> findByItemId(String itemId);
}

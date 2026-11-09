package com.hospio.inventory.repository;

import com.hospio.inventory.entity.StockCount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockCountRepository extends JpaRepository<StockCount, String> {
    List<StockCount> findByHotelIdOrderByCreatedAtDesc(String hotelId);
    Optional<StockCount> findFirstByStoreIdAndStatusIn(String storeId, java.util.Collection<StockCount.Status> statuses);
}

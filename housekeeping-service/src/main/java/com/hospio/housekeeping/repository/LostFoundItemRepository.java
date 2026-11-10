package com.hospio.housekeeping.repository;

import com.hospio.housekeeping.entity.LostFoundItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LostFoundItemRepository extends JpaRepository<LostFoundItem, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<LostFoundItem> {
    List<LostFoundItem> findByHotelIdOrderByFoundAtDesc(String hotelId);
}

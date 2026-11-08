package com.hospio.inventory.repository;

import com.hospio.inventory.entity.Store;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, String> {
    List<Store> findByHotelIdAndActiveTrueOrderByName(String hotelId);
}

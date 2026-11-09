package com.hospio.maintenance.repository;

import com.hospio.maintenance.entity.Asset;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetRepository extends JpaRepository<Asset, String> {
    List<Asset> findByHotelIdOrderByName(String hotelId);
}

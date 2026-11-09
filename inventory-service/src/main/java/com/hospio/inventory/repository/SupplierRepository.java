package com.hospio.inventory.repository;

import com.hospio.inventory.entity.Supplier;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, String> {
    List<Supplier> findByHotelIdAndActiveTrueOrderByName(String hotelId);
    List<Supplier> findByHotelIdOrderByName(String hotelId);
}

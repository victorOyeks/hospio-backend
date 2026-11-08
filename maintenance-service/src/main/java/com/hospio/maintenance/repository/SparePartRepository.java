package com.hospio.maintenance.repository;

import com.hospio.maintenance.entity.SparePart;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SparePartRepository extends JpaRepository<SparePart, String> {
    List<SparePart> findByHotelIdOrderByName(String hotelId);
}

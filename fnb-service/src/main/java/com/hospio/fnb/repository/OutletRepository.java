package com.hospio.fnb.repository;

import com.hospio.fnb.entity.Outlet;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutletRepository extends JpaRepository<Outlet, String> {
    List<Outlet> findByHotelIdOrderByName(String hotelId);
}

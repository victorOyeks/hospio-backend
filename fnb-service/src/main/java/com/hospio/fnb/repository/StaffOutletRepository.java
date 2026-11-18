package com.hospio.fnb.repository;

import com.hospio.fnb.entity.StaffOutlet;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffOutletRepository extends JpaRepository<StaffOutlet, String> {
    List<StaffOutlet> findByHotelIdAndUserId(String hotelId, String userId);

    List<StaffOutlet> findByHotelId(String hotelId);

    List<StaffOutlet> findByOutletId(String outletId);
}

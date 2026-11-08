package com.hospio.tenant.repository;

import com.hospio.tenant.entity.Hotel;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HotelRepository extends JpaRepository<Hotel, String> {
    List<Hotel> findByBusinessIdOrderByName(String businessId);

    int countByBusinessId(String businessId);
}

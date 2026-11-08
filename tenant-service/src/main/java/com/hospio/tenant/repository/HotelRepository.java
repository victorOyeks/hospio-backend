package com.hospio.tenant.repository;

import com.hospio.tenant.entity.Hotel;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HotelRepository extends JpaRepository<Hotel, String> {
    List<Hotel> findByBusinessIdOrderByName(String businessId);

    int countByBusinessId(String businessId);

    @org.springframework.data.jpa.repository.Query("select coalesce(sum(h.roomCount), 0) from Hotel h")
    long sumRoomCount();
}

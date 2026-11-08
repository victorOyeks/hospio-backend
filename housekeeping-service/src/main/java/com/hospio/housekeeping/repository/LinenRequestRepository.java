package com.hospio.housekeeping.repository;

import com.hospio.housekeeping.entity.LinenRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinenRequestRepository extends JpaRepository<LinenRequest, String> {
    List<LinenRequest> findByHotelIdAndStatusOrderByCreatedAt(String hotelId, String status);
}

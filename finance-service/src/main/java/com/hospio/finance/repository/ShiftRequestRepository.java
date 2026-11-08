package com.hospio.finance.repository;

import com.hospio.finance.entity.ShiftRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShiftRequestRepository extends JpaRepository<ShiftRequest, String> {
    List<ShiftRequest> findByHotelIdAndStatusOrderByCreatedAt(String hotelId, ShiftRequest.Status status);
}

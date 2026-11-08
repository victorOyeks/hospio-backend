package com.hospio.inventory.repository;

import com.hospio.inventory.entity.StockRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockRequestRepository extends JpaRepository<StockRequest, String> {
    List<StockRequest> findByHotelIdAndStatusOrderByCreatedAt(String hotelId, StockRequest.Status status);

    List<StockRequest> findByHotelIdAndFromTeamOrderByCreatedAtDesc(String hotelId, String fromTeam);
}

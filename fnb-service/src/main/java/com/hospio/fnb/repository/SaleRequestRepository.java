package com.hospio.fnb.repository;

import com.hospio.fnb.entity.SaleRequest;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleRequestRepository extends JpaRepository<SaleRequest, String> {
    Optional<SaleRequest> findByApprovalId(String approvalId);

    List<SaleRequest> findByOrderIdOrderByCreatedAtDesc(String orderId);

    List<SaleRequest> findByOrderIdAndStatus(String orderId, String status);

    List<SaleRequest> findByHotelIdAndStatusOrderByCreatedAtDesc(String hotelId, String status);

    List<SaleRequest> findTop50ByHotelIdAndRequestedByOrderByCreatedAtDesc(String hotelId, String requestedBy);

    List<SaleRequest> findTop100ByHotelIdOrderByCreatedAtDesc(String hotelId);
}

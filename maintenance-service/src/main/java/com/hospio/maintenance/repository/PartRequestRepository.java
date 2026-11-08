package com.hospio.maintenance.repository;

import com.hospio.maintenance.entity.PartRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartRequestRepository extends JpaRepository<PartRequest, String> {
    List<PartRequest> findByHotelIdOrderByCreatedAtDesc(String hotelId);

    Optional<PartRequest> findByApprovalId(String approvalId);
}

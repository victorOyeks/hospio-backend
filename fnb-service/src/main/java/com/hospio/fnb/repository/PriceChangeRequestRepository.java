package com.hospio.fnb.repository;

import com.hospio.fnb.entity.PriceChangeRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceChangeRequestRepository extends JpaRepository<PriceChangeRequest, String> {
    Optional<PriceChangeRequest> findByApprovalId(String approvalId);
}

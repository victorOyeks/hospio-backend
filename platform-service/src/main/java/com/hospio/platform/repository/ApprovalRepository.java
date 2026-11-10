package com.hospio.platform.repository;

import com.hospio.platform.entity.Approval;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRepository extends JpaRepository<Approval, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Approval> {
    List<Approval> findByBusinessIdAndStatusOrderByCreatedAt(String businessId, com.hospio.common.enums.ApprovalStatus status);

    List<Approval> findByBusinessIdOrderByCreatedAtDesc(String businessId);

    long countByHotelIdAndStatus(String hotelId, com.hospio.common.enums.ApprovalStatus status);
}

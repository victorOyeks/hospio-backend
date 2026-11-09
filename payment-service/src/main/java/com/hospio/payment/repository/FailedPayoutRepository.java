package com.hospio.payment.repository;

import com.hospio.payment.entity.FailedPayout;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FailedPayoutRepository extends JpaRepository<FailedPayout, String> {
    Page<FailedPayout> findByResolvedAtIsNullOrderByCreatedAtDesc(Pageable pageable);

    Optional<FailedPayout> findFirstBySettlementIdAndResolvedAtIsNull(String settlementId);
}

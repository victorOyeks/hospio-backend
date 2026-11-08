package com.hospio.folio.repository;

import com.hospio.folio.entity.PendingDiscount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingDiscountRepository extends JpaRepository<PendingDiscount, String> {
    Optional<PendingDiscount> findByApprovalId(String approvalId);
}

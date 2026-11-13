package com.hospio.folio.repository;

import com.hospio.folio.entity.PendingDiscount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingDiscountRepository extends JpaRepository<PendingDiscount, String> {
    Optional<PendingDiscount> findByApprovalId(String approvalId);

    /** Locked, so a decision delivered twice at once is applied once. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from PendingDiscount p where p.approvalId = :approvalId")
    Optional<PendingDiscount> lockByApprovalId(@org.springframework.data.repository.query.Param("approvalId") String approvalId);
}

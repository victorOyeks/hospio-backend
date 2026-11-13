package com.hospio.payment.repository;

import com.hospio.payment.entity.Refund;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, String>, JpaSpecificationExecutor<Refund> {
    Optional<Refund> findByApprovalId(String approvalId);

    /** The refund, locked until the transaction ends: approve, decline and the owner's answer decide it one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> lockById(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.approvalId = :approvalId")
    Optional<Refund> lockByApprovalId(@Param("approvalId") String approvalId);

    List<Refund> findByPaymentId(String paymentId);
}

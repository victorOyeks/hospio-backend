package com.hospio.payment.repository;

import com.hospio.payment.entity.Refund;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RefundRepository extends JpaRepository<Refund, String>, JpaSpecificationExecutor<Refund> {
    List<Refund> findByHotelIdOrderByCreatedAtDesc(String hotelId);

    Optional<Refund> findByApprovalId(String approvalId);

    List<Refund> findByPaymentId(String paymentId);
}

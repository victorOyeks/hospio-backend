package com.hospio.payment.repository;

import com.hospio.payment.entity.Refund;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, String> {
    List<Refund> findByHotelIdOrderByCreatedAtDesc(String hotelId);

    Optional<Refund> findByApprovalId(String approvalId);
}

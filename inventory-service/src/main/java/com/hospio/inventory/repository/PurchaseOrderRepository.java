package com.hospio.inventory.repository;

import com.hospio.inventory.entity.PurchaseOrder;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, String> {
    List<PurchaseOrder> findByHotelIdOrderByCreatedAtDesc(String hotelId);

    Optional<PurchaseOrder> findByApprovalId(String approvalId);

    long countByHotelId(String hotelId);
}

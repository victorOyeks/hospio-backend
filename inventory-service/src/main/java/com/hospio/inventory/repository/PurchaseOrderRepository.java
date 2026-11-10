package com.hospio.inventory.repository;

import com.hospio.inventory.entity.PurchaseOrder;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<PurchaseOrder> {
    List<PurchaseOrder> findByHotelIdOrderByCreatedAtDesc(String hotelId);

    Optional<PurchaseOrder> findByApprovalId(String approvalId);

    long countByHotelId(String hotelId);
    List<PurchaseOrder> findBySupplierIdAndStatusIn(String supplierId, java.util.Collection<PurchaseOrder.Status> statuses);

    List<PurchaseOrder> findByHotelIdAndStatusIn(String hotelId, java.util.Collection<PurchaseOrder.Status> statuses);
}

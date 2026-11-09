package com.hospio.inventory.repository;

import com.hospio.inventory.entity.Delivery;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryRepository extends JpaRepository<Delivery, String> {
    List<Delivery> findByHotelIdOrderByCreatedAtDesc(String hotelId);
    List<Delivery> findByPurchaseOrderIdOrderByCreatedAt(String purchaseOrderId);
}

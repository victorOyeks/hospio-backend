package com.hospio.housekeeping.repository;

import com.hospio.housekeeping.entity.LaundryOrder;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LaundryOrderRepository extends JpaRepository<LaundryOrder, String> {
    List<LaundryOrder> findByHotelIdAndStatusInOrderByCreatedAt(String hotelId, java.util.Collection<LaundryOrder.Status> statuses);

    List<LaundryOrder> findByHotelIdOrderByCreatedAtDesc(String hotelId);
}

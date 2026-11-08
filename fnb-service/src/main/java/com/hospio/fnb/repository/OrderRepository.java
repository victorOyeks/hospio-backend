package com.hospio.fnb.repository;

import com.hospio.fnb.entity.Order;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, String> {
    List<Order> findByOutletIdAndStatusInOrderByCreatedAt(String outletId, java.util.Collection<Order.Status> statuses);

    List<Order> findByHotelIdAndStatusInOrderByCreatedAt(String hotelId, java.util.Collection<Order.Status> statuses);

    List<Order> findByHotelIdAndCreatedAtAfterOrderByCreatedAtDesc(String hotelId, java.time.Instant since);

    long countByHotelId(String hotelId);
}

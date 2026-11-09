package com.hospio.fnb.repository;

import com.hospio.fnb.entity.OrderLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderLineRepository extends JpaRepository<OrderLine, String> {
    List<OrderLine> findByOrderIdOrderByCreatedAt(String orderId);

    List<OrderLine> findByOrderIdIn(java.util.Collection<String> orderIds);
}

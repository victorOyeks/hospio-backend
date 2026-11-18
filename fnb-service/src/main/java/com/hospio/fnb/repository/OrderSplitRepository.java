package com.hospio.fnb.repository;

import com.hospio.fnb.entity.OrderSplit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderSplitRepository extends JpaRepository<OrderSplit, String> {
    Optional<OrderSplit> findFirstByOrderIdAndStatus(String orderId, String status);

    List<OrderSplit> findByOrderIdInAndStatus(Collection<String> orderIds, String status);

    List<OrderSplit> findByOrderIdOrderByCreatedAtDesc(String orderId);
}

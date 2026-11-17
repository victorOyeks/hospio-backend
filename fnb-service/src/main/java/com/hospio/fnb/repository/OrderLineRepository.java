package com.hospio.fnb.repository;

import com.hospio.fnb.entity.OrderLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderLineRepository extends JpaRepository<OrderLine, String> {
    List<OrderLine> findByOrderIdOrderByCreatedAt(String orderId);

    List<OrderLine> findByOrderIdIn(java.util.Collection<String> orderIds);

    /** Lines another outlet sold that this one makes (kitchen tickets), in the given states; read inside the hotel (row security). */
    List<OrderLine> findByPreparedAtAndStatusIn(String preparedAt, java.util.Collection<OrderLine.Status> statuses);
}

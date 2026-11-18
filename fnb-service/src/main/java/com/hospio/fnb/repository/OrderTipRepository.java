package com.hospio.fnb.repository;

import com.hospio.fnb.entity.OrderTip;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderTipRepository extends JpaRepository<OrderTip, String> {
    boolean existsByPaymentId(String paymentId);

    List<OrderTip> findByOrderId(String orderId);
}

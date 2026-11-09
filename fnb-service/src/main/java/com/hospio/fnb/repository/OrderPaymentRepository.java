package com.hospio.fnb.repository;

import com.hospio.fnb.entity.OrderPayment;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderPaymentRepository extends JpaRepository<OrderPayment, String> {
    boolean existsByPaymentId(String paymentId);

    List<OrderPayment> findByOrderIdIn(Collection<String> orderIds);
}

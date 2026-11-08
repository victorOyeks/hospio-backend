package com.hospio.payment.repository;

import com.hospio.payment.entity.Receipt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptRepository extends JpaRepository<Receipt, String> {
    Optional<Receipt> findByPaymentId(String paymentId);

    long countByHotelId(String hotelId);
}

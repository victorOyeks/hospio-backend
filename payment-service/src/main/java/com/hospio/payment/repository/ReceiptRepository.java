package com.hospio.payment.repository;

import com.hospio.payment.entity.Receipt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptRepository extends JpaRepository<Receipt, String> {
    Optional<Receipt> findByPaymentId(String paymentId);

    long countByHotelId(String hotelId);

    /** The highest receipt number this hotel has issued with the given prefix (e.g. "RCP-2026-"), if any. */
    @org.springframework.data.jpa.repository.Query("select max(r.number) from Receipt r where r.hotelId = :hotelId and r.number like concat(:prefix, '%')")
    java.util.Optional<String> maxNumber(@org.springframework.data.repository.query.Param("hotelId") String hotelId, @org.springframework.data.repository.query.Param("prefix") String prefix);
}

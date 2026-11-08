package com.hospio.finance.repository;

import com.hospio.finance.entity.RevenueEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevenueEntryRepository extends JpaRepository<RevenueEntry, String> {
    Optional<RevenueEntry> findByPaymentId(String paymentId);

    List<RevenueEntry> findByHotelIdAndBusinessDate(String hotelId, java.time.LocalDate date);

    List<RevenueEntry> findByHotelIdAndBusinessDateBetween(String hotelId, java.time.LocalDate from, java.time.LocalDate to);
}

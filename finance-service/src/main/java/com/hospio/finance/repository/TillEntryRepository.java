package com.hospio.finance.repository;

import com.hospio.finance.entity.TillEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TillEntryRepository extends JpaRepository<TillEntry, String> {
    List<TillEntry> findByTillIdOrderByCreatedAt(String tillId);

    Optional<TillEntry> findByPaymentId(String paymentId);
}

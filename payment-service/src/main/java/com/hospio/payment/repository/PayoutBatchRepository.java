package com.hospio.payment.repository;

import com.hospio.payment.entity.PayoutBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutBatchRepository extends JpaRepository<PayoutBatch, String> {
    Page<PayoutBatch> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByRefStartingWith(String prefix);
}

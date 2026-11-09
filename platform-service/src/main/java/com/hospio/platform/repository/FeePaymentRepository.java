package com.hospio.platform.repository;

import com.hospio.platform.entity.FeePayment;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeePaymentRepository extends JpaRepository<FeePayment, String> {
    List<FeePayment> findByStatementIdOrderByPaidOnAsc(String statementId);

    List<FeePayment> findByStatementIdInOrderByPaidOnAsc(Collection<String> statementIds);
}

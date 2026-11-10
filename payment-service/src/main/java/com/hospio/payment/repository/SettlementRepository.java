package com.hospio.payment.repository;

import com.hospio.payment.entity.Settlement;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SettlementRepository extends JpaRepository<Settlement, String>, JpaSpecificationExecutor<Settlement> {
    boolean existsByBusinessIdAndProviderAndPeriodDate(String businessId, String provider, LocalDate periodDate);

    boolean existsByPeriodDate(LocalDate periodDate);

    List<Settlement> findByStatusOrderByPeriodDateAsc(Settlement.Status status);

    List<Settlement> findByIdIn(Collection<String> ids);

    List<Settlement> findByPayoutBatchIdOrderByBusinessNameAsc(String payoutBatchId);

    List<Settlement> findByBusinessIdOrderByPeriodDateDesc(String businessId);

    List<Settlement> findByBusinessIdAndPeriodDateBetweenOrderByPeriodDateDesc(String businessId, java.time.LocalDate from, java.time.LocalDate to);
}

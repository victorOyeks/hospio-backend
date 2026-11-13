package com.hospio.payment.repository;

import com.hospio.payment.entity.Settlement;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementRepository extends JpaRepository<Settlement, String>, JpaSpecificationExecutor<Settlement> {
    boolean existsByBusinessIdAndProviderAndPeriodDate(String businessId, String provider, LocalDate periodDate);

    boolean existsByPeriodDate(LocalDate periodDate);

    List<Settlement> findByStatusOrderByPeriodDateAsc(Settlement.Status status);

    List<Settlement> findByIdIn(Collection<String> ids);

    /**
     * Puts the settlements still PENDING into a payout batch; returns how many it moved. Fewer than asked means another batch took some
     * a moment ago (the caller aborts).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Settlement s set s.status = com.hospio.payment.entity.Settlement.Status.SETTLED, s.payoutBatchId = :batchId, s.settledAt = null, "
            + "s.updatedAt = :now where s.id in :ids and s.status = com.hospio.payment.entity.Settlement.Status.PENDING")
    int claimForBatch(@Param("ids") Collection<String> ids, @Param("batchId") String batchId, @Param("now") java.time.Instant now);

    List<Settlement> findByPayoutBatchIdOrderByBusinessNameAsc(String payoutBatchId);

    List<Settlement> findByBusinessIdOrderByPeriodDateDesc(String businessId);

    List<Settlement> findByBusinessIdAndPeriodDateBetweenOrderByPeriodDateDesc(String businessId, java.time.LocalDate from, java.time.LocalDate to);
}

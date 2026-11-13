package com.hospio.platform.repository;

import com.hospio.platform.entity.FeeStatement;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeeStatementRepository extends JpaRepository<FeeStatement, String>, JpaSpecificationExecutor<FeeStatement> {
    List<FeeStatement> findByBusinessIdOrderByMonthDesc(String businessId);

    List<FeeStatement> findByStatusOrderByMonthDesc(String status);

    List<FeeStatement> findByStatusIn(Collection<String> statuses);

    List<FeeStatement> findByMonth(String month);

    List<FeeStatement> findByBusinessIdAndStatusIn(String businessId, Collection<String> statuses);

    Optional<FeeStatement> findByBusinessIdAndMonth(String businessId, String month);

    long countByBusinessIdAndStatus(String businessId, String status);

    /** The statement locked FOR UPDATE until the transaction ends: payments recorded at once queue instead of overwriting each other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from FeeStatement s where s.id = :id")
    Optional<FeeStatement> lockById(@Param("id") String id);

    /** What is still owed (fee − paid) over statements in these statuses, summed in the database. */
    @Query("select coalesce(sum(s.feeAmount - s.paidAmount), 0) from FeeStatement s where s.status in :statuses")
    BigDecimal sumOutstanding(@Param("statuses") Collection<String> statuses);

    /** Fees billed for a month (yyyy-MM), summed in the database. */
    @Query("select coalesce(sum(s.feeAmount), 0) from FeeStatement s where s.month = :month")
    BigDecimal sumBilled(@Param("month") String month);

    /**
     * The nightly OVERDUE rule as one guarded update: DUE or PART_PAID, money still owed, due date before the cutoff (today − grace days).
     * A payment recorded while it runs is never overwritten: the row is re-checked as it is updated.
     */
    @Modifying
    @Query("update FeeStatement s set s.status = 'OVERDUE', s.updatedAt = :now where s.status in ('DUE', 'PART_PAID') and s.dueOn < :cutoff "
            + "and s.feeAmount > 0 and s.paidAmount < s.feeAmount")
    int markOverdue(@Param("cutoff") LocalDate cutoff, @Param("now") Instant now);

    /** DUE or PART_PAID statements that are in fact settled (nothing billed, or paid in full) become PAID. */
    @Modifying
    @Query("update FeeStatement s set s.status = 'PAID', s.updatedAt = :now where s.status in ('DUE', 'PART_PAID') "
            + "and (s.feeAmount = 0 or s.paidAmount >= s.feeAmount)")
    int markSettled(@Param("now") Instant now);
}

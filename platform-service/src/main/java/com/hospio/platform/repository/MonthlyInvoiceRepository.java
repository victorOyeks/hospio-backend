package com.hospio.platform.repository;

import com.hospio.platform.entity.MonthlyInvoice;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonthlyInvoiceRepository extends JpaRepository<MonthlyInvoice, String> {

    List<MonthlyInvoice> findByBusinessIdOrderByMonthDescIssuedAtDesc(String businessId);

    List<MonthlyInvoice> findByBusinessIdAndStatusIn(String businessId, Collection<String> statuses);

    Optional<MonthlyInvoice> findFirstByBusinessIdAndMonthAndStatusNot(String businessId, String month, String status);

    List<MonthlyInvoice> findByStatusIn(Collection<String> statuses);

    List<MonthlyInvoice> findAllByOrderByMonthDescNumberAsc();

    Optional<MonthlyInvoice> findByNumber(String number);

    Optional<MonthlyInvoice> findByPaystackRef(String reference);

    /** CARRIED_FORWARD invoices of a business not rolled into another yet. */
    List<MonthlyInvoice> findByBusinessIdAndStatusAndCarriedIntoIdIsNull(String businessId, String status);

    List<MonthlyInvoice> findByCarriedIntoId(String invoiceId);

    /** Serialises numbering for one month (two instances, or an admin run racing the 1st). */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Object lock(@Param("key") String key);

    @Query(value = "SELECT COALESCE(MAX(CAST(split_part(number, '-', 3) AS INTEGER)), 0) FROM platform.monthly_invoices WHERE number LIKE :prefix",
            nativeQuery = true)
    int lastNumber(@Param("prefix") String prefix);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from MonthlyInvoice i where i.id = :id")
    Optional<MonthlyInvoice> lockById(@Param("id") String id);
}

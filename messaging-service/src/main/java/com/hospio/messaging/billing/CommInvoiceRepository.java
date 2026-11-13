package com.hospio.messaging.billing;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommInvoiceRepository extends JpaRepository<CommInvoice, String>, JpaSpecificationExecutor<CommInvoice> {

    Optional<CommInvoice> findByHotelIdAndMonth(String hotelId, String month);

    Optional<CommInvoice> findByPaymentReference(String reference);

    Optional<CommInvoice> findByNumber(String number);

    List<CommInvoice> findByStatusAndDueOnBefore(CommInvoice.Status status, LocalDate day);

    List<CommInvoice> findByHotelIdAndStatusIn(String hotelId, Collection<CommInvoice.Status> statuses);

    Page<CommInvoice> findByHotelIdOrderByMonthDesc(String hotelId, Pageable page);

    List<CommInvoice> findByHotelIdInAndStatusIn(Collection<String> hotelIds, Collection<CommInvoice.Status> statuses);

    /** Serialises invoice numbering for one month across instances (and an admin "issue now" racing the cron). */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Object lock(@Param("key") String key);

    /** The highest number used in a month (MSG-YYYYMM-0001 → 1), 0 when none. */
    @Query(value = "SELECT COALESCE(MAX(CAST(split_part(number, '-', 3) AS INTEGER)), 0) FROM messaging.comm_invoices WHERE month = :month "
            + "AND number LIKE 'MSG-%'", nativeQuery = true)
    int lastNumber(@Param("month") String month);

    /** The row, locked until the transaction ends: settling one invoice twice at once (redirect + webhook) waits here. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from CommInvoice i where i.id = :id")
    Optional<CommInvoice> lockById(@Param("id") String id);

    /** DUE → OVERDUE only if it is still DUE (a payment in the meantime wins). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CommInvoice i set i.status = :to, i.updatedAt = :now where i.id = :id and i.status = :from")
    int moveStatus(@Param("id") String id, @Param("from") CommInvoice.Status from, @Param("to") CommInvoice.Status to, @Param("now") Instant now);

    /** The checkout reference of an open invoice (never touches a paid one). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CommInvoice i set i.paymentReference = :ref, i.updatedAt = :now where i.id = :id and i.status in :open")
    int setPaymentReference(@Param("id") String id, @Param("ref") String reference, @Param("open") Collection<CommInvoice.Status> open,
                            @Param("now") Instant now);
}

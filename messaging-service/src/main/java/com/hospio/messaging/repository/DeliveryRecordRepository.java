package com.hospio.messaging.repository;

import com.hospio.messaging.entity.DeliveryRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryRecordRepository extends JpaRepository<DeliveryRecord, String>, JpaSpecificationExecutor<DeliveryRecord> {

    Optional<DeliveryRecord> findByEventId(String eventId);

    /** The SMS or WhatsApp a delivery report is about ("sms:<id>" / "whatsapp:<id>"). */
    Optional<DeliveryRecord> findFirstByProviderMessageId(String providerMessageId);

    // ---- the send queue (docs/email.md, "Sending") ----

    /**
     * Up to {@code limit} QUEUED deliveries that are due, locked for this transaction; rows another worker (or instance) holds
     * are skipped, not waited for. The caller leases them ({@link #lease}) before committing.
     */
    @Query(value = "SELECT id FROM messaging.deliveries WHERE status = 'QUEUED' AND next_attempt_at <= :now ORDER BY next_attempt_at "
            + "LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<String> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    /** Claims locked rows until {@code until}: no one else picks them up before then; a worker that died lets them go again. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DeliveryRecord d set d.nextAttemptAt = :until, d.attempts = d.attempts + 1 where d.id in :ids")
    int lease(@Param("ids") java.util.Collection<String> ids, @Param("until") Instant until);

    // ---- guest-message billing (docs/guest-message-billing.md) ----

    /** A hotel's billable messages that went out (sent or delivered) in [from, to) and are on no invoice yet: [via, count, amount]. */
    @Query("select d.sentVia, count(d), coalesce(sum(d.unitPrice), 0) from DeliveryRecord d where d.hotelId = :hotel and d.billable = true "
            + "and d.invoiceId is null and d.status in :statuses and d.createdAt >= :from and d.createdAt < :to group by d.sentVia")
    List<Object[]> unbilledUsage(@Param("hotel") String hotelId, @Param("statuses") List<DeliveryRecord.Status> statuses,
                                 @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Puts on the invoice every billable message of the hotel that went out before {@code to} and is on no invoice yet, with no
     * lower bound: a message of an earlier month that only turned SENT/DELIVERED after that month's invoice was issued (a late
     * delivery report) rolls into this one instead of never being billed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DeliveryRecord d set d.invoiceId = :invoice where d.hotelId = :hotel and d.billable = true and d.invoiceId is null "
            + "and d.status in :statuses and d.createdAt < :to")
    int attachToInvoice(@Param("invoice") String invoiceId, @Param("hotel") String hotelId, @Param("statuses") List<DeliveryRecord.Status> statuses,
                        @Param("to") Instant to);

    /** What is on an invoice, per channel: [via, count, amount]. */
    @Query("select d.sentVia, count(d), coalesce(sum(d.unitPrice), 0) from DeliveryRecord d where d.invoiceId = :invoice group by d.sentVia")
    List<Object[]> invoiceUsage(@Param("invoice") String invoiceId);

    /** Several hotels' billable messages in [from, to) not invoiced yet: [hotelId, via, count, amount]. */
    @Query("select d.hotelId, d.sentVia, count(d), coalesce(sum(d.unitPrice), 0) from DeliveryRecord d where d.hotelId in :hotels and d.billable = true "
            + "and d.invoiceId is null and d.status in :statuses and d.createdAt >= :from and d.createdAt < :to group by d.hotelId, d.sentVia")
    List<Object[]> unbilledUsageOf(@Param("hotels") java.util.Collection<String> hotelIds, @Param("statuses") List<DeliveryRecord.Status> statuses,
                                   @Param("from") Instant from, @Param("to") Instant to);

    /** An invoice's lines: [kind, via, count, amount]. */
    @Query("select d.kind, d.sentVia, count(d), coalesce(sum(d.unitPrice), 0) from DeliveryRecord d where d.invoiceId = :invoice "
            + "group by d.kind, d.sentVia order by d.kind, d.sentVia")
    List<Object[]> invoiceLines(@Param("invoice") String invoiceId);

    Page<DeliveryRecord> findByInvoiceId(String invoiceId, Pageable page);

    /** This month's billable messages so far (not invoiced yet), newest first. */
    @Query("select d from DeliveryRecord d where d.hotelId = :hotel and d.billable = true and d.invoiceId is null and d.status in :statuses "
            + "and d.createdAt >= :from order by d.createdAt desc")
    Page<DeliveryRecord> unbilled(@Param("hotel") String hotelId, @Param("statuses") List<DeliveryRecord.Status> statuses,
                                  @Param("from") Instant from, Pageable page);

    /** Hotels with billable messages before {@code to} not yet invoiced (stragglers of earlier months included): [hotelId, businessId]. */
    @Query("select distinct d.hotelId, d.businessId from DeliveryRecord d where d.billable = true and d.invoiceId is null "
            + "and d.status in :statuses and d.createdAt < :to and d.hotelId is not null")
    List<Object[]> hotelsToBill(@Param("statuses") List<DeliveryRecord.Status> statuses, @Param("to") Instant to);
}

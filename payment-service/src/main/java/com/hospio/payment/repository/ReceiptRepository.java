package com.hospio.payment.repository;

import com.hospio.payment.entity.Receipt;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReceiptRepository extends JpaRepository<Receipt, String> {
    /** A payment's receipt: the first issued, so a payment that got two before receipts.payment_id was unique still reads as one. */
    Optional<Receipt> findFirstByPaymentIdOrderByCreatedAtAsc(String paymentId);

    /** The receipts of several payments in one query (a sale's or a folio's receipts). */
    List<Receipt> findByPaymentIdIn(Collection<String> paymentIds);

    long countByHotelId(String hotelId);

    /** The highest receipt number this hotel has issued with the given prefix (e.g. "RCP-2026-"), if any. */
    @Query("select max(r.number) from Receipt r where r.hotelId = :hotelId and r.number like concat(:prefix, '%')")
    Optional<String> maxNumber(@Param("hotelId") String hotelId, @Param("prefix") String prefix);

    /** Serialises receipt numbering per hotel until the transaction ends (then max + 1 can't hand out one number twice). */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Object lock(@Param("key") String key);
}

package com.hospio.folio.repository;

import com.hospio.folio.entity.Invoice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Invoice> {
    List<Invoice> findByOrganizationIdOrderByDueDateDesc(String organizationId);

    long countByHotelId(String hotelId);

    /** The highest sequence among the hotel's invoices numbered {@code prefix}NNNNN (prefix like INV-2026-); 0 when none. */
    @org.springframework.data.jpa.repository.Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(number FROM LENGTH(:prefix) + 1) AS INTEGER)), 0) "
            + "FROM folio.invoices WHERE hotel_id = :hotelId AND number LIKE :prefix || '%' "
            + "AND SUBSTRING(number FROM LENGTH(:prefix) + 1) ~ '^[0-9]+$'", nativeQuery = true)
    int lastNumber(@org.springframework.data.repository.query.Param("hotelId") String hotelId,
                   @org.springframework.data.repository.query.Param("prefix") String prefix);

    /** Serialises invoice numbering per hotel until the transaction ends. */
    @org.springframework.data.jpa.repository.Query(value = "select 1 from (select pg_advisory_xact_lock(hashtext(:key))) l", nativeQuery = true)
    Object advisoryLock(@org.springframework.data.repository.query.Param("key") String key);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> lockById(@org.springframework.data.repository.query.Param("id") String id);
}

package com.hospio.platform.repository;

import com.hospio.platform.entity.DeviceInvoice;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceInvoiceRepository extends JpaRepository<DeviceInvoice, String> {

    List<DeviceInvoice> findByPackageIdOrderByDueOnDescKindAsc(String packageId);

    boolean existsByPackageIdAndKindAndSequence(String packageId, String kind, int sequence);

    List<DeviceInvoice> findByStatusInAndDueOnLessThanEqualOrderByDueOnAsc(Collection<String> statuses, LocalDate dueOn);

    List<DeviceInvoice> findByHotelIdAndStatusIn(String hotelId, Collection<String> statuses);

    Optional<DeviceInvoice> findByPaystackRef(String paystackRef);

    @Query("select count(i) from DeviceInvoice i where i.number like concat(:prefix, '%')")
    long countByNumberPrefix(@Param("prefix") String prefix);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from DeviceInvoice i where i.id = :id")
    Optional<DeviceInvoice> lockById(@Param("id") String id);

    /** Open schedule items of a business due by {@code to} and not on a monthly invoice yet. */
    List<DeviceInvoice> findByBusinessIdAndMonthlyInvoiceIdIsNullAndStatusInAndDueOnLessThanEqualOrderByDueOnAsc(String businessId,
            Collection<String> statuses, LocalDate to);

    List<DeviceInvoice> findByMonthlyInvoiceId(String monthlyInvoiceId);

    /** The highest number used with this prefix (DEV-YYYYMM-0001 → 1), 0 when none. */
    @Query(value = "SELECT COALESCE(MAX(CAST(split_part(number, '-', 3) AS INTEGER)), 0) FROM platform.device_invoices WHERE number LIKE :prefix",
            nativeQuery = true)
    int lastNumber(@Param("prefix") String prefix);
}

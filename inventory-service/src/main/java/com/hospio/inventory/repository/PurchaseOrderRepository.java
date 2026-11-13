package com.hospio.inventory.repository;

import com.hospio.inventory.entity.PurchaseOrder;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<PurchaseOrder> {
    List<PurchaseOrder> findByHotelIdOrderByCreatedAtDesc(String hotelId);

    Optional<PurchaseOrder> findByApprovalId(String approvalId);

    long countByHotelId(String hotelId);
    List<PurchaseOrder> findBySupplierIdAndStatusIn(String supplierId, java.util.Collection<PurchaseOrder.Status> statuses);

    List<PurchaseOrder> findByHotelIdAndStatusIn(String hotelId, java.util.Collection<PurchaseOrder.Status> statuses);

    /** The row locked FOR UPDATE until the transaction ends: taken before the status checks of a change, so two at once queue. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from PurchaseOrder x where x.id = :id")
    Optional<PurchaseOrder> lockById(@Param("id") String id);

    /** The unpaged list (internal callers, docs/pagination.md): created since a date, plus every open order however old. */
    @Query("select p from PurchaseOrder p where p.hotelId = :hotelId and (p.createdAt >= :since or p.status in :open) order by p.createdAt desc")
    List<PurchaseOrder> recentOrOpen(@Param("hotelId") String hotelId, @Param("since") java.time.Instant since,
                                     @Param("open") java.util.Collection<PurchaseOrder.Status> open);

    /** Per supplier: open orders and the last order date (the suppliers list), without loading every order. */
    @Query("select p.supplierId, sum(case when p.status in :open then 1 else 0 end), max(p.createdAt) from PurchaseOrder p "
            + "where p.hotelId = :hotelId group by p.supplierId")
    List<Object[]> supplierStats(@Param("hotelId") String hotelId, @Param("open") java.util.Collection<PurchaseOrder.Status> open);

    /** The highest PO-{year}-NNNNN number of this hotel and year (0 when none). Called under {@link #lockNumbers}. */
    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(number FROM 9) AS INTEGER)), 0) FROM inventory.purchase_orders WHERE hotel_id = :hotelId "
            + "AND number ~ ('^PO-' || :year || '-[0-9]+$')", nativeQuery = true)
    int lastNumber(@Param("hotelId") String hotelId, @Param("year") String year);

    /** Per-hotel transaction lock while a number is chosen and saved (released at commit). */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Object lockNumbers(@Param("key") String key);
}

package com.hospio.inventory.repository;

import com.hospio.inventory.entity.StockMovement;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMovementRepository extends JpaRepository<StockMovement, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<StockMovement> {
    List<StockMovement> findByHotelIdOrderByCreatedAtDesc(String hotelId);

    boolean existsByReferenceAndKind(String reference, StockMovement.Kind kind);

    List<StockMovement> findByHotelIdAndReferenceOrderByCreatedAt(String hotelId, String reference);

    List<StockMovement> findByHotelIdAndCreatedAtBetweenOrderByCreatedAtDesc(String hotelId, java.time.Instant from, java.time.Instant to);

    List<StockMovement> findTop30ByItemIdOrderByCreatedAtDesc(String itemId);

    /**
     * Per stock item and kind (USED, WASTE, COUNTED, ADJUSTED) over [from, to): the summed quantity (negative = out), split by whether the
     * movement was drawn by a sale (reference ORDER-…). One grouped statement for the venue "stock used vs sold" report. Times are UTC wall time.
     */
    @org.springframework.data.jpa.repository.Query(value = "SELECT m.item_id AS item_id, m.kind AS kind, (COALESCE(m.reference, '') LIKE 'ORDER-%') AS sale,"
            + " SUM(m.quantity) AS qty FROM inventory.stock_movements m WHERE m.hotel_id = :hotelId AND m.created_at >= :from AND m.created_at < :to"
            + " AND m.kind IN ('USED', 'WASTE', 'COUNTED', 'ADJUSTED') GROUP BY m.item_id, m.kind, (COALESCE(m.reference, '') LIKE 'ORDER-%')",
            nativeQuery = true)
    List<Object[]> usageByItem(@org.springframework.data.repository.query.Param("hotelId") String hotelId,
                               @org.springframework.data.repository.query.Param("from") java.time.LocalDateTime from,
                               @org.springframework.data.repository.query.Param("to") java.time.LocalDateTime to);
}

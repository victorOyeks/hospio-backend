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
}

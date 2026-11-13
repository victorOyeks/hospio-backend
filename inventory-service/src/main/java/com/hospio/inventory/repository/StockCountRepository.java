package com.hospio.inventory.repository;

import com.hospio.inventory.entity.StockCount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface StockCountRepository extends JpaRepository<StockCount, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<StockCount> {
    List<StockCount> findByHotelIdOrderByCreatedAtDesc(String hotelId);
    Optional<StockCount> findFirstByStoreIdAndStatusIn(String storeId, java.util.Collection<StockCount.Status> statuses);

    /** The row locked FOR UPDATE until the transaction ends: taken before the status checks of a change, so two at once queue. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from StockCount x where x.id = :id")
    Optional<StockCount> lockById(@Param("id") String id);

    /** The unpaged list (internal callers, docs/pagination.md): started since a date, plus every count still in progress. */
    @Query("select c from StockCount c where c.hotelId = :hotelId and (c.createdAt >= :since or c.status in :open) order by c.createdAt desc")
    List<StockCount> recentOrOpen(@Param("hotelId") String hotelId, @Param("since") java.time.Instant since,
                                  @Param("open") java.util.Collection<StockCount.Status> open);
}

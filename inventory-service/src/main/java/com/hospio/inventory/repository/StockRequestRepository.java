package com.hospio.inventory.repository;

import com.hospio.inventory.entity.StockRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface StockRequestRepository extends JpaRepository<StockRequest, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<StockRequest> {
    List<StockRequest> findByHotelIdAndStatusOrderByCreatedAt(String hotelId, StockRequest.Status status);

    List<StockRequest> findByHotelIdAndFromTeamOrderByCreatedAtDesc(String hotelId, String fromTeam);
    List<StockRequest> findByHotelIdOrderByCreatedAtDesc(String hotelId);

    /** The row locked FOR UPDATE until the transaction ends: taken before the status checks of a change, so two at once queue. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from StockRequest x where x.id = :id")
    Optional<StockRequest> lockById(@Param("id") String id);

    /** The unpaged list (internal callers, docs/pagination.md): asked since a date, plus every request still open. */
    @Query("select r from StockRequest r where r.hotelId = :hotelId and (r.createdAt >= :since or r.status in :open) order by r.createdAt desc")
    List<StockRequest> recentOrOpen(@Param("hotelId") String hotelId, @Param("since") java.time.Instant since,
                                    @Param("open") java.util.Collection<StockRequest.Status> open);
}

package com.hospio.housekeeping.repository;

import com.hospio.housekeeping.entity.LinenBatch;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LinenBatchRepository extends JpaRepository<LinenBatch, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<LinenBatch> {
    List<LinenBatch> findByHotelIdOrderByCreatedAtDesc(String hotelId);

    List<LinenBatch> findByHotelIdAndUpdatedAtBetween(String hotelId, Instant from, Instant to);

    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(number FROM 5) AS INTEGER)), 0) FROM housekeeping.linen_batches WHERE hotel_id = :hotelId",
            nativeQuery = true)
    int lastNumber(@Param("hotelId") String hotelId);
}

package com.hospio.housekeeping.repository;

import com.hospio.housekeeping.entity.LaundryOrder;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LaundryOrderRepository extends JpaRepository<LaundryOrder, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<LaundryOrder> {
    List<LaundryOrder> findByHotelIdAndStatusInOrderByCreatedAt(String hotelId, Collection<LaundryOrder.Status> statuses);

    List<LaundryOrder> findByHotelIdOrderByCreatedAtDesc(String hotelId);

    List<LaundryOrder> findByHotelIdAndCreatedAtBetween(String hotelId, Instant from, Instant to);

    List<LaundryOrder> findByHotelIdAndChargedAtBetween(String hotelId, Instant from, Instant to);

    List<LaundryOrder> findByHotelIdAndPaidAtBetween(String hotelId, Instant from, Instant to);

    boolean existsByPaymentId(String paymentId);

    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(number FROM 5) AS INTEGER)), 0) FROM housekeeping.laundry_orders WHERE hotel_id = :hotelId "
            + "AND number LIKE 'LDR-%'", nativeQuery = true)
    int lastNumber(@Param("hotelId") String hotelId);

    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Object lock(@Param("key") String key);
}

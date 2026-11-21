package com.hospio.finance.repository;

import com.hospio.finance.entity.Bill;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BillRepository extends JpaRepository<Bill, String>, JpaSpecificationExecutor<Bill> {

    /** The supplier bill made from a delivery (at most one). */
    Optional<Bill> findFirstByDeliveryId(String deliveryId);

    boolean existsByDeliveryId(String deliveryId);

    /** Unpaid bills made from deliveries since a moment, for the double-payment hint on the expense form. */
    List<Bill> findByHotelIdAndStatusAndDeliveryIdIsNotNullAndCreatedAtGreaterThanEqual(String hotelId, Bill.Status status, Instant since);
}

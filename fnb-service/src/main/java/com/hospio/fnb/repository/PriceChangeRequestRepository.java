package com.hospio.fnb.repository;

import com.hospio.fnb.entity.PriceChangeRequest;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceChangeRequestRepository extends JpaRepository<PriceChangeRequest, String> {
    Optional<PriceChangeRequest> findByApprovalId(String approvalId);

    /** The requests in {@code status} for these items, newest first. */
    List<PriceChangeRequest> findByMenuItemIdInAndStatusOrderByCreatedAtDesc(Collection<String> menuItemIds, String status);

    /** How many requests of the location are in {@code status} (the venue dashboard's price changes waiting). */
    long countByHotelIdAndStatus(String hotelId, String status);
}

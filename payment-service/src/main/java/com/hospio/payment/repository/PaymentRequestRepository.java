package com.hospio.payment.repository;

import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.PaymentStatus;
import com.hospio.payment.entity.PaymentRequest;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, String>, JpaSpecificationExecutor<PaymentRequest> {
    List<PaymentRequest> findByHotelIdAndRequestedByOrderByCreatedAtDesc(String hotelId, String requestedBy);

    List<PaymentRequest> findByHotelIdAndRoleOrderByCreatedAtDesc(String hotelId, com.hospio.common.enums.Role role);

    List<PaymentRequest> findByHotelIdOrderByCreatedAtDesc(String hotelId);

    List<PaymentRequest> findByFolioIdOrderByCreatedAtDesc(String folioId);
    List<PaymentRequest> findByBookingIdOrderByCreatedAtDesc(String bookingId);

    Optional<PaymentRequest> findByProviderRef(String providerRef);

    List<PaymentRequest> findByHotelIdAndStatusIn(String hotelId, Collection<PaymentStatus> statuses);

    /** Payments approved in a window (by when they resolved, or were created for cash recorded on the spot). */
    @Query("select p from PaymentRequest p where p.status = :status and p.method in :methods "
            + "and coalesce(p.resolvedAt, p.createdAt) >= :from and coalesce(p.resolvedAt, p.createdAt) < :to")
    List<PaymentRequest> approvedBetween(@Param("status") PaymentStatus status, @Param("methods") Collection<PaymentMethod> methods,
                                         @Param("from") Instant from, @Param("to") Instant to);

    @Query("select p from PaymentRequest p where p.status = :status and p.businessId = :businessId "
            + "and coalesce(p.resolvedAt, p.createdAt) >= :from and coalesce(p.resolvedAt, p.createdAt) < :to")
    List<PaymentRequest> approvedForBusiness(@Param("status") PaymentStatus status, @Param("businessId") String businessId,
                                             @Param("from") Instant from, @Param("to") Instant to);

    @Query("select p from PaymentRequest p where p.status = :status and p.hotelId = :hotelId "
            + "and coalesce(p.resolvedAt, p.createdAt) >= :from and coalesce(p.resolvedAt, p.createdAt) < :to")
    List<PaymentRequest> approvedForHotel(@Param("status") PaymentStatus status, @Param("hotelId") String hotelId,
                                          @Param("from") Instant from, @Param("to") Instant to);
}

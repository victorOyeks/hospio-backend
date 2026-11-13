package com.hospio.payment.repository;

import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.PaymentStatus;
import com.hospio.payment.entity.PaymentRequest;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, String>, JpaSpecificationExecutor<PaymentRequest> {
    List<PaymentRequest> findByHotelIdAndRoleOrderByCreatedAtDesc(String hotelId, com.hospio.common.enums.Role role);

    List<PaymentRequest> findByFolioIdOrderByCreatedAtDesc(String folioId);
    List<PaymentRequest> findByBookingIdOrderByCreatedAtDesc(String bookingId);

    List<PaymentRequest> findByOrderIdAndStatusOrderByCreatedAtAsc(String orderId, PaymentStatus status);

    List<PaymentRequest> findByFolioIdAndStatusOrderByCreatedAtAsc(String folioId, PaymentStatus status);

    Optional<PaymentRequest> findByProviderRef(String providerRef);

    List<PaymentRequest> findByHotelIdAndStatusIn(String hotelId, Collection<PaymentStatus> statuses);

    long countByHotelIdAndStatusIn(String hotelId, Collection<PaymentStatus> statuses);

    /** The unpaged lists (scripts): the hotel's payments since a day, newest first. */
    List<PaymentRequest> findByHotelIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(String hotelId, Instant since);

    List<PaymentRequest> findByHotelIdAndRequestedByAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(String hotelId, String requestedBy, Instant since);

    /** The payment, locked until the transaction ends: the webhook, Verify and Cancel resolve a pending payment one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentRequest p where p.id = :id")
    Optional<PaymentRequest> lockById(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentRequest p where p.providerRef = :ref")
    Optional<PaymentRequest> lockByProviderRef(@Param("ref") String providerRef);

    /** Approved payments in a window summed per business, hotel, method and provider (settlements: no entity per payment). */
    @Query("select new com.hospio.payment.repository.ApprovedTotal(p.businessId, p.hotelId, p.method, p.provider, sum(p.amount), count(p)) "
            + "from PaymentRequest p where p.status = :status and p.method in :methods and p.businessId is not null "
            + "and coalesce(p.resolvedAt, p.createdAt) >= :from and coalesce(p.resolvedAt, p.createdAt) < :to "
            + "group by p.businessId, p.hotelId, p.method, p.provider")
    List<ApprovedTotal> approvedTotalsBetween(@Param("status") PaymentStatus status, @Param("methods") Collection<PaymentMethod> methods,
                                              @Param("from") Instant from, @Param("to") Instant to);

    @Query("select new com.hospio.payment.repository.ApprovedTotal(p.businessId, p.hotelId, p.method, p.provider, sum(p.amount), count(p)) "
            + "from PaymentRequest p where p.status = :status and p.businessId = :businessId "
            + "and coalesce(p.resolvedAt, p.createdAt) >= :from and coalesce(p.resolvedAt, p.createdAt) < :to "
            + "group by p.businessId, p.hotelId, p.method, p.provider")
    List<ApprovedTotal> approvedTotalsForBusiness(@Param("status") PaymentStatus status, @Param("businessId") String businessId,
                                                  @Param("from") Instant from, @Param("to") Instant to);

    /** [count, sum] of a hotel's payments with the status in a window. */
    @Query("select count(p), sum(p.amount) from PaymentRequest p where p.status = :status and p.hotelId = :hotelId "
            + "and coalesce(p.resolvedAt, p.createdAt) >= :from and coalesce(p.resolvedAt, p.createdAt) < :to")
    List<Object[]> approvedCountAndSumForHotel(@Param("status") PaymentStatus status, @Param("hotelId") String hotelId,
                                               @Param("from") Instant from, @Param("to") Instant to);

}

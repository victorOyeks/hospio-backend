package com.hospio.payment.repository;

import com.hospio.payment.entity.PaymentRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, String> {
    List<PaymentRequest> findByHotelIdAndRequestedByOrderByCreatedAtDesc(String hotelId, String requestedBy);

    List<PaymentRequest> findByHotelIdAndRoleOrderByCreatedAtDesc(String hotelId, com.hospio.common.enums.Role role);

    List<PaymentRequest> findByHotelIdOrderByCreatedAtDesc(String hotelId);

    List<PaymentRequest> findByFolioIdOrderByCreatedAtDesc(String folioId);
    List<PaymentRequest> findByBookingIdOrderByCreatedAtDesc(String bookingId);

    Optional<PaymentRequest> findByProviderRef(String providerRef);

    List<PaymentRequest> findByHotelIdAndStatusIn(String hotelId, java.util.Collection<com.hospio.common.enums.PaymentStatus> statuses);
}

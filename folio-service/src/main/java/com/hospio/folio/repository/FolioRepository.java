package com.hospio.folio.repository;

import com.hospio.folio.entity.Folio;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FolioRepository extends JpaRepository<Folio, String> {
    List<Folio> findByHotelIdAndStatusOrderByRoomNumber(String hotelId, Folio.Status status);

    Optional<Folio> findFirstByBookingIdAndStatus(String bookingId, Folio.Status status);

    List<Folio> findByBookingIdOrderByOpenedAt(String bookingId);

    Optional<Folio> findFirstByRoomIdAndStatus(String roomId, Folio.Status status);

    List<Folio> findByOrganizationIdOrderByOpenedAtDesc(String organizationId);
}

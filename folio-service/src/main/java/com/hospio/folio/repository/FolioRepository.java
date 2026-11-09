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

    long countByHotelIdAndStatus(String hotelId, Folio.Status status);

    @org.springframework.data.jpa.repository.Query("select coalesce(sum(f.balance), 0) from Folio f where f.hotelId = :hotelId and f.status = 'OPEN'")
    java.math.BigDecimal openBalance(@org.springframework.data.repository.query.Param("hotelId") String hotelId);

    @org.springframework.data.jpa.repository.Query("select f from Folio f where f.hotelId = :hotelId and f.status = 'OPEN' and f.balance > 0 order by f.roomNumber")
    List<Folio> openWithBalance(@org.springframework.data.repository.query.Param("hotelId") String hotelId);
}

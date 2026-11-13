package com.hospio.folio.repository;

import com.hospio.folio.entity.Folio;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FolioRepository extends JpaRepository<Folio, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Folio> {
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

    /** The folio with its row locked until the transaction ends: every change to a folio's lines or totals takes this first. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select f from Folio f where f.id = :id")
    Optional<Folio> lockById(@org.springframework.data.repository.query.Param("id") String id);

    /** Ids only (nothing loaded unlocked): the booking's open folios, oldest first. */
    @org.springframework.data.jpa.repository.Query("select f.id from Folio f where f.bookingId = :bookingId and f.status = 'OPEN' order by f.openedAt, f.id")
    List<String> openIdsForBooking(@org.springframework.data.repository.query.Param("bookingId") String bookingId);

    /** Ids only: the room's open folios, oldest first. */
    @org.springframework.data.jpa.repository.Query("select f.id from Folio f where f.roomId = :roomId and f.status = 'OPEN' order by f.openedAt, f.id")
    List<String> openIdsForRoom(@org.springframework.data.repository.query.Param("roomId") String roomId);

    /** Serialises check-ins of one booking so a replayed GuestCheckedIn never opens a second folio. */
    @org.springframework.data.jpa.repository.Query(value = "select 1 from (select pg_advisory_xact_lock(hashtext(:key))) l", nativeQuery = true)
    Object advisoryLock(@org.springframework.data.repository.query.Param("key") String key);

    /** What an organization's stays still owe, summed in the database. */
    @org.springframework.data.jpa.repository.Query("select coalesce(sum(f.balance), 0) from Folio f where f.organizationId = :orgId")
    java.math.BigDecimal organizationBalance(@org.springframework.data.repository.query.Param("orgId") String orgId);

    /** What an organization's stays opened in [from, to) still owe. */
    @org.springframework.data.jpa.repository.Query("select coalesce(sum(f.balance), 0) from Folio f where f.organizationId = :orgId and f.openedAt >= :from and f.openedAt < :to")
    java.math.BigDecimal organizationBalance(@org.springframework.data.repository.query.Param("orgId") String orgId,
                                             @org.springframework.data.repository.query.Param("from") java.time.Instant from,
                                             @org.springframework.data.repository.query.Param("to") java.time.Instant to);
}

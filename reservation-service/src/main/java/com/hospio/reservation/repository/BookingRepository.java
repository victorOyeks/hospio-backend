package com.hospio.reservation.repository;

import com.hospio.common.enums.BookingStatus;
import com.hospio.reservation.entity.Booking;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, String> {

    Optional<Booking> findByReference(String reference);

    /** The booking, its row locked until the transaction ends: every state change starts here, before its status checks. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> lockById(@Param("id") String id);

    /** The id for a reference (no entity loaded, so a following {@link #lockById} reads the row fresh). */
    @Query("select b.id from Booking b where b.reference = :reference")
    Optional<String> idByReference(@Param("reference") String reference);

    /**
     * Records that a payment or refund ({@code ref}) was applied to the booking; 1 when recorded now, 0 when it already was (or the
     * booking does not exist). Written in the same transaction as {@link #addPaid}, so each payment counts once.
     */
    @Modifying
    @Query(value = "insert into reservation.booking_payments (payment_ref, booking_id, business_id, hotel_id, amount) "
            + "select :ref, b.id, b.business_id, b.hotel_id, :amount from reservation.bookings b where b.id = :id "
            + "on conflict (payment_ref) do nothing", nativeQuery = true)
    int recordPayment(@Param("ref") String ref, @Param("id") String bookingId, @Param("amount") BigDecimal amount);

    /** Adds {@code delta} to what was paid (never below zero) in one statement, without loading the booking. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Booking b set b.amountPaid = greatest(b.amountPaid + :delta, 0), b.version = b.version + 1, b.updatedAt = :now where b.id = :id")
    int addPaid(@Param("id") String bookingId, @Param("delta") BigDecimal delta, @Param("now") Instant now);

    /** Ids of BOOKED stays whose arrival date is on or before {@code date} (the night audit locks each before marking it). */
    @Query("select b.id from Booking b where b.hotelId = :hotelId and b.status = 'BOOKED' and b.checkInDate <= :date")
    List<String> unresolvedArrivalIds(@Param("hotelId") String hotelId, @Param("date") LocalDate date);

    /** Room nights and their value over [from, toExclusive) for a business: only the nights inside the range, at each stay's rate. */
    @Query(value = "select coalesce(sum(n), 0) as nights, coalesce(sum(n * coalesce(nightly_rate, 0)), 0) as value from ("
            + "select greatest(0, least(b.check_out_date, :toExclusive) - greatest(b.check_in_date, :from)) as n, b.nightly_rate "
            + "from reservation.bookings b where b.business_id = :businessId and b.status in ('BOOKED','CHECKED_IN','CHECKED_OUT') "
            + "and b.check_in_date < :toExclusive and b.check_out_date > :from) s", nativeQuery = true)
    List<Object[]> nightsInBusiness(@Param("businessId") String businessId, @Param("from") LocalDate from,
                                    @Param("toExclusive") LocalDate toExclusive);

    List<Booking> findByHotelIdAndStatusOrderByCheckInDate(String hotelId, BookingStatus status);

    List<Booking> findByGuestIdOrderByCheckInDateDesc(String guestId);

    Optional<Booking> findFirstByRoomIdAndStatus(String roomId, BookingStatus status);

    /**
     * Active stays on a room that may hold it on a night of [from, to): BOOKED stays touching those nights and every CHECKED_IN stay,
     * whatever its dates (a guest holds the room until checked out). {@link com.hospio.reservation.service.Occupancy} decides which do.
     */
    @Query("select b from Booking b where b.roomId = :roomId and b.checkInDate < :to "
            + "and ((b.status = 'BOOKED' and b.checkOutDate > :from) or b.status = 'CHECKED_IN')")
    List<Booking> activeOnRoom(@Param("roomId") String roomId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** The same over every stay of a room type at a hotel (assigned or not). */
    @Query("select b from Booking b where b.hotelId = :hotelId and b.roomTypeId = :typeId and b.checkInDate < :to "
            + "and ((b.status = 'BOOKED' and b.checkOutDate > :from) or b.status = 'CHECKED_IN')")
    List<Booking> activeOnType(@Param("hotelId") String hotelId, @Param("typeId") String roomTypeId, @Param("from") LocalDate from,
                               @Param("to") LocalDate to);

    /** The same over every room of a hotel that has a room assigned (availability's free and turnover rooms). */
    @Query("select b from Booking b where b.hotelId = :hotelId and b.roomId is not null and b.checkInDate < :to "
            + "and ((b.status = 'BOOKED' and b.checkOutDate > :from) or b.status = 'CHECKED_IN')")
    List<Booking> activeWithRoom(@Param("hotelId") String hotelId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Who is checked in to a room right now (at most one, by the partial unique index ux_bookings_room_in_house). */
    List<Booking> findByRoomIdAndStatus(String roomId, BookingStatus status);

    @Query("select b from Booking b where b.hotelId = :hotelId and ((b.checkInDate = :date and b.status = 'BOOKED') "
            + "or (b.checkOutDate = :date and b.status = 'CHECKED_IN') or (b.status = 'CHECKED_IN')) order by b.roomNumber")
    List<Booking> today(@Param("hotelId") String hotelId, @Param("date") LocalDate date);

    @Query("select b from Booking b where b.hotelId = :hotelId and b.checkInDate <= :to and b.checkOutDate >= :from "
            + "and (:status is null or b.status = :status) order by b.checkInDate")
    List<Booking> between(@Param("hotelId") String hotelId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                          @Param("status") BookingStatus status);

    /** Every stay of a business (any hotel) that was not cancelled or a no-show and touches [from, toExclusive). */
    @Query("select b from Booking b where b.businessId = :businessId and b.status in ('BOOKED','CHECKED_IN','CHECKED_OUT') "
            + "and b.checkInDate < :toExclusive and b.checkOutDate > :from")
    List<Booking> overlappingInBusiness(@Param("businessId") String businessId, @Param("from") LocalDate from,
                                        @Param("toExclusive") LocalDate toExclusive);

    long countByHotelIdAndStatus(String hotelId, BookingStatus status);

    /** Every booking ever placed in a room, whatever its status (property refuses to delete a room that has any). */
    long countByRoomId(String roomId);

    long countByHotelIdAndStatusAndCheckInDate(String hotelId, BookingStatus status, LocalDate checkInDate);

    long countByHotelIdAndStatusAndCheckOutDate(String hotelId, BookingStatus status, LocalDate checkOutDate);

    long countByHotelIdAndCreatedAtGreaterThanEqual(String hotelId, java.time.Instant since);

    @Query(value = "select pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Object lock(@Param("key") String key);

    /** BOOKED stays whose arrival date is on or before {@code date}: still unresolved at the end of that day. */
    @Query("select b from Booking b where b.hotelId = :hotelId and b.status = 'BOOKED' and b.checkInDate <= :date order by b.checkInDate, b.roomNumber")
    List<Booking> unresolvedArrivals(@Param("hotelId") String hotelId, @Param("date") LocalDate date);

    @Query("select b from Booking b where b.hotelId = :hotelId and b.status = 'BOOKED' and b.checkInDate = :date order by b.roomNumber")
    List<Booking> bookedArrivalsOn(@Param("hotelId") String hotelId, @Param("date") LocalDate date);

    /** Stays that were expected on {@code date}: anything with that arrival date that was not cancelled. */
    @Query("select count(b) from Booking b where b.hotelId = :hotelId and b.checkInDate = :date and b.status <> 'CANCELLED'")
    long countExpectedArrivals(@Param("hotelId") String hotelId, @Param("date") LocalDate date);

    @Query("select count(b) from Booking b where b.hotelId = :hotelId and b.checkOutDate = :date and b.status in ('CHECKED_IN','CHECKED_OUT')")
    long countDepartures(@Param("hotelId") String hotelId, @Param("date") LocalDate date);

    @Query("select b from Booking b where b.hotelId = :hotelId and b.status = 'CHECKED_IN' and b.checkOutDate <= :date order by b.roomNumber")
    List<Booking> unresolvedDepartures(@Param("hotelId") String hotelId, @Param("date") LocalDate date);

    @Query("select b from Booking b where b.hotelId = :hotelId and b.checkOutDate = :date and b.status in ('CHECKED_IN','CHECKED_OUT') order by b.roomNumber")
    List<Booking> departuresOn(@Param("hotelId") String hotelId, @Param("date") LocalDate date);
}

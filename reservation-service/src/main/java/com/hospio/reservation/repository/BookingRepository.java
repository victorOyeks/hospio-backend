package com.hospio.reservation.repository;

import com.hospio.common.enums.BookingStatus;
import com.hospio.reservation.entity.Booking;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, String> {

    Optional<Booking> findByReference(String reference);

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

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

    /** Stays that overlap [from, to) on a room: a booking blocks the nights it covers, not its check-out day. */
    @Query("select b from Booking b where b.roomId = :roomId and b.status in ('BOOKED','CHECKED_IN') "
            + "and b.checkInDate < :to and b.checkOutDate > :from and (:exclude is null or b.id <> :exclude)")
    List<Booking> overlappingOnRoom(@Param("roomId") String roomId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                                    @Param("exclude") String excludeBookingId);

    @Query("select count(b) from Booking b where b.hotelId = :hotelId and b.roomTypeId = :typeId and b.status in ('BOOKED','CHECKED_IN') "
            + "and b.checkInDate < :to and b.checkOutDate > :from and (:exclude is null or b.id <> :exclude)")
    long countOverlappingOnType(@Param("hotelId") String hotelId, @Param("typeId") String roomTypeId, @Param("from") LocalDate from,
                                @Param("to") LocalDate to, @Param("exclude") String excludeBookingId);

    @Query("select b from Booking b where b.hotelId = :hotelId and ((b.checkInDate = :date and b.status = 'BOOKED') "
            + "or (b.checkOutDate = :date and b.status = 'CHECKED_IN') or (b.status = 'CHECKED_IN')) order by b.roomNumber")
    List<Booking> today(@Param("hotelId") String hotelId, @Param("date") LocalDate date);

    @Query("select b from Booking b where b.hotelId = :hotelId and b.checkInDate <= :to and b.checkOutDate >= :from "
            + "and (:status is null or b.status = :status) order by b.checkInDate")
    List<Booking> between(@Param("hotelId") String hotelId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                          @Param("status") BookingStatus status);

    @Query(value = "select pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Object lock(@Param("key") String key);
}

package com.hospio.reservation.service;

import com.hospio.common.enums.BookingStatus;
import com.hospio.common.paging.Paging;
import com.hospio.reservation.entity.Booking;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * Who holds a room, and for which nights (docs/occupancy.md).
 *
 * <p>A BOOKED stay holds the nights it covers, [check-in, check-out). A CHECKED_IN stay holds its room until the guest is checked
 * out, not until its departure date passes: a guest past their departure date (an overstay) still holds tonight, whatever the dates
 * say. The one exception is same-day turnover: a guest due out today does not hold tonight against an arrival being given the room,
 * but nobody can be checked in to the room until that guest is checked out ({@link #inHouseOn}).
 */
public final class Occupancy {

    private Occupancy() {
    }

    /** The hotel's business day: Lagos time, whatever zone the server runs in. */
    public static LocalDate today() {
        return LocalDate.now(Paging.LAGOS);
    }

    /** The night after which the stay stops holding its room: its check-out date, or tomorrow for a guest still in past departure. */
    static LocalDate holdsUntil(Booking b, LocalDate today) {
        if (b.getStatus() == BookingStatus.CHECKED_IN && b.getCheckOutDate().isBefore(today)) {
            return today.plusDays(1);
        }
        return b.getCheckOutDate();
    }

    /** Does the stay hold its room on any night of [from, to)? Cancelled, no-show and checked-out stays hold nothing. */
    public static boolean blocks(Booking b, LocalDate from, LocalDate to, LocalDate today) {
        if (b.getStatus() != BookingStatus.BOOKED && b.getStatus() != BookingStatus.CHECKED_IN) {
            return false;
        }
        return b.getCheckInDate().isBefore(to) && holdsUntil(b, today).isAfter(from);
    }

    /** The first stay among {@code stays} (other than {@code excludeId}) that holds the room on a night of [from, to). */
    public static Optional<Booking> firstBlocking(Collection<Booking> stays, LocalDate from, LocalDate to, LocalDate today, String excludeId) {
        return stays.stream().filter(b -> !Objects.equals(b.getId(), excludeId)).filter(b -> blocks(b, from, to, today)).findFirst();
    }

    /** How many of {@code stays} (other than {@code excludeId}) hold a room on a night of [from, to). */
    public static long countBlocking(Collection<Booking> stays, LocalDate from, LocalDate to, LocalDate today, String excludeId) {
        return stays.stream().filter(b -> !Objects.equals(b.getId(), excludeId)).filter(b -> blocks(b, from, to, today)).count();
    }

    /** A guest (other than {@code excludeId}) checked in to {@code roomId} right now, whatever their dates. */
    public static Optional<Booking> inHouseOn(Collection<Booking> stays, String roomId, String excludeId) {
        return stays.stream().filter(b -> b.getStatus() == BookingStatus.CHECKED_IN && Objects.equals(roomId, b.getRoomId())
                && !Objects.equals(b.getId(), excludeId)).findFirst();
    }

    public enum RoomState {
        /** Nobody holds the room on those nights and nobody is in it. */
        FREE,
        /** A guest due out today is still checked in: the room can be given to an arrival, who checks in once that guest checks out. */
        TURNOVER,
        /** Booked or occupied on those nights. */
        TAKEN
    }

    /** The room's state for a stay of [from, to), given every active stay on the room. */
    public static RoomState state(Collection<Booking> roomStays, String roomId, LocalDate from, LocalDate to, LocalDate today) {
        if (firstBlocking(roomStays, from, to, today, null).isPresent()) {
            return RoomState.TAKEN;
        }
        if (!from.isAfter(today) && inHouseOn(roomStays, roomId, null).isPresent()) {
            return RoomState.TURNOVER;
        }
        return RoomState.FREE;
    }

    /** The refusal when somebody is still checked in to the room. */
    public static String stillInHouse(String roomNumber, Booking occupant) {
        return "Room " + roomNumber + " still has a guest checked in (" + occupant.getGuestName() + "). Check them out first, or choose another room.";
    }

    /** The refusal when the room is held by a stay on those nights: in house (an overstay included) or another booking. */
    public static String taken(String roomNumber, Booking holder) {
        if (holder.getStatus() == BookingStatus.CHECKED_IN) {
            return stillInHouse(roomNumber, holder);
        }
        return "Room " + roomNumber + " is already booked for those nights";
    }
}

package com.hospio.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.common.enums.BookingStatus;
import com.hospio.reservation.entity.Booking;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A guest holds the room until checked out; a guest due out today leaves the room to today's arrival only as a turnover. */
class OccupancyTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 11);

    static Booking stay(String id, String room, LocalDate in, LocalDate out, BookingStatus status) {
        Booking b = Booking.builder().reference(id).guestId("g").guestName("Guest " + id).roomTypeId("t1").roomId(room).checkInDate(in)
                .checkOutDate(out).status(status).nightlyRate(BigDecimal.ONE).totalAmount(BigDecimal.ONE).build();
        b.setId(id);
        return b;
    }

    @Test
    void aBookingHoldsItsNightsButNotItsCheckOutDay() {
        Booking b = stay("b", "r1", TODAY, TODAY.plusDays(2), BookingStatus.BOOKED);
        assertThat(Occupancy.blocks(b, TODAY.plusDays(1), TODAY.plusDays(3), TODAY)).isTrue();
        assertThat(Occupancy.blocks(b, TODAY.plusDays(2), TODAY.plusDays(3), TODAY)).isFalse();
    }

    @Test
    void aGuestDueOutTodayIsATurnoverNotFree() {
        Booking dep = stay("d", "r1", TODAY.minusDays(1), TODAY, BookingStatus.CHECKED_IN);
        assertThat(Occupancy.blocks(dep, TODAY, TODAY.plusDays(1), TODAY)).isFalse();
        assertThat(Occupancy.state(List.of(dep), "r1", TODAY, TODAY.plusDays(1), TODAY)).isEqualTo(Occupancy.RoomState.TURNOVER);
        assertThat(Occupancy.inHouseOn(List.of(dep), "r1", "arrival")).contains(dep);
        // A stay from tomorrow is not held up by today's departure.
        assertThat(Occupancy.state(List.of(dep), "r1", TODAY.plusDays(1), TODAY.plusDays(2), TODAY)).isEqualTo(Occupancy.RoomState.FREE);
    }

    @Test
    void anOverstayHoldsTonight() {
        Booking over = stay("o", "r1", TODAY.minusDays(4), TODAY.minusDays(1), BookingStatus.CHECKED_IN);
        assertThat(Occupancy.blocks(over, TODAY, TODAY.plusDays(1), TODAY)).isTrue();
        assertThat(Occupancy.state(List.of(over), "r1", TODAY, TODAY.plusDays(2), TODAY)).isEqualTo(Occupancy.RoomState.TAKEN);
        assertThat(Occupancy.taken("101", over)).startsWith("Room 101 still has a guest checked in (Guest o)");
    }

    @Test
    void checkedOutCancelledAndNoShowHoldNothing() {
        for (BookingStatus s : List.of(BookingStatus.CHECKED_OUT, BookingStatus.CANCELLED, BookingStatus.NO_SHOW)) {
            Booking b = stay("x", "r1", TODAY.minusDays(1), TODAY.plusDays(2), s);
            assertThat(Occupancy.blocks(b, TODAY, TODAY.plusDays(1), TODAY)).isFalse();
            assertThat(Occupancy.inHouseOn(List.of(b), "r1", null)).isEmpty();
        }
    }

    @Test
    void aStayNeverBlocksItself() {
        Booking self = stay("s", "r1", TODAY, TODAY.plusDays(2), BookingStatus.BOOKED);
        assertThat(Occupancy.firstBlocking(List.of(self), TODAY, TODAY.plusDays(2), TODAY, "s")).isEmpty();
        assertThat(Occupancy.countBlocking(List.of(self), TODAY, TODAY.plusDays(2), TODAY, null)).isEqualTo(1);
    }
}

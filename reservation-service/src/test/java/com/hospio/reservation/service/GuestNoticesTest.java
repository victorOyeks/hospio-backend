package com.hospio.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.reservation.dto.BookingResponse;
import com.hospio.reservation.entity.Booking;
import com.hospio.reservation.entity.Guest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** "Change dates, guest told": only when the dates move, to the guest's phone. */
class GuestNoticesTest {

    private final GuestService guests = mock(GuestService.class);
    private final DeliveryService deliveries = mock(DeliveryService.class);
    private final GuestNotices notices = new GuestNotices(guests, deliveries);

    private static BookingResponse booking(LocalDate in, LocalDate out) {
        Booking b = Booking.builder().reference("BK-1001").guestId("g1").guestName("Ada Okeke").roomTypeName("Deluxe")
                .checkInDate(in).checkOutDate(out).nightlyRate(new BigDecimal("45000")).totalAmount(new BigDecimal("90000")).build();
        b.setId("b1");
        return BookingResponse.of(b);
    }

    @Test
    void newDatesGoToTheGuestOnWhatsApp() {
        Guest g = Guest.builder().fullName("Ada Okeke").phone("+2348030000001").build();
        when(guests.get("g1")).thenReturn(g);
        when(deliveries.send(any(Delivery.class))).thenAnswer(i -> List.of((Delivery) i.getArgument(0)));

        List<Delivery> sent = notices.datesChanged(booking(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 22)),
                booking(LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 24)));

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).channel()).isEqualTo(Delivery.Channel.WHATSAPP);
        assertThat(sent.get(0).to()).isEqualTo("+2348030000001");
        assertThat(sent.get(0).text()).isEqualTo("Hello Ada, your stay (BK-1001) is now Sat 21 Nov to Tue 24 Nov, 3 nights. Reply to this message if that is not right.");
    }

    @Test
    void sameDatesOrNoPhoneSendNothing() {
        BookingResponse same = booking(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 22));
        assertThat(notices.datesChanged(same, same)).isEmpty();
        when(guests.get("g1")).thenReturn(Guest.builder().fullName("Ada").build());
        assertThat(notices.datesChanged(same, booking(LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 22)))).isEmpty();
        verify(deliveries, never()).send(any(Delivery.class));
    }
}

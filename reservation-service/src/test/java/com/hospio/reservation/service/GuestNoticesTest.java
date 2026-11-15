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
    private final StayLinks links = new StayLinks("test-secret", "https://hospio360.com/stay/");
    private final com.hospio.reservation.client.GuestMessagingSettings settings = mock(com.hospio.reservation.client.GuestMessagingSettings.class);
    private final GuestNotices notices = new GuestNotices(guests, deliveries, links, settings);

    @org.junit.jupiter.api.BeforeEach
    void allOn() {
        when(settings.forHotel(any())).thenReturn(com.hospio.common.setup.GuestMessaging.DEFAULTS);
    }
    private static final String ID = "4bd3010f-0111-4707-b189-1caeaf78a143";

    private static BookingResponse booking(LocalDate in, LocalDate out) {
        Booking b = Booking.builder().reference("BK-1001").guestId("g1").guestName("Ada Okeke").roomTypeName("Deluxe")
                .checkInDate(in).checkOutDate(out).nightlyRate(new BigDecimal("45000")).totalAmount(new BigDecimal("90000")).build();
        b.setId(ID);
        return BookingResponse.of(b);
    }

    @Test
    void newDatesGoToTheGuestOnWhatsApp() {
        Guest g = Guest.builder().fullName("Ada Okeke").phone("+2348030000001").build();
        when(guests.get("g1")).thenReturn(g);
        when(deliveries.send(org.mockito.ArgumentMatchers.<List<Delivery>>any())).thenAnswer(i -> i.getArgument(0));

        List<Delivery> sent = notices.datesChanged(booking(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 22)),
                booking(LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 24)));

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).channel()).isEqualTo(Delivery.Channel.WHATSAPP);
        assertThat(sent.get(0).to()).isEqualTo("+2348030000001");
        // WhatsApp goes as the approved template; its text is the one-page SMS it falls back to (@hotel = the hotel's name).
        // The last value is the booking page link's ending, for the "View booking" button; @hotelPhone the hotel's phone.
        assertThat(sent.get(0).template()).isEqualTo("hospio_booking_changed_v2");
        assertThat(sent.get(0).params()).containsExactly("Ada", "@hotel", "BK-1001", "Sat 21 Nov", "Tue 24 Nov", "@hotelPhone", links.token(ID).orElseThrow());
        assertThat(links.bookingId(sent.get(0).params().get(6))).contains(ID);
        // The SMS it falls back to ends with the booking page link, kept whole on one page even for a long hotel name.
        String link = "https://hospio360.com/stay/" + links.token(ID).orElseThrow();
        assertThat(sent.get(0).text()).isEqualTo("@hotel: Hello Ada, booking BK-1001 is now Sat 21 Nov to Tue 24 Nov. Details and directions: " + link);
        String sms = com.hospio.common.delivery.SmsText.fit(sent.get(0).text().replace("@hotel", "Royal Suites Ikeja Victoria Island"));
        assertThat(com.hospio.common.delivery.SmsText.length(sms)).isLessThanOrEqualTo(160);
        assertThat(sms).endsWith(link).contains("BK-1001");
    }

    @Test
    void sameDatesOrNoPhoneSendNothing() {
        BookingResponse same = booking(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 22));
        assertThat(notices.datesChanged(same, same)).isEmpty();
        when(guests.get("g1")).thenReturn(Guest.builder().fullName("Ada").build());
        assertThat(notices.datesChanged(same, booking(LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 22)))).isEmpty();
        verify(deliveries, never()).send(org.mockito.ArgumentMatchers.<List<Delivery>>any());
    }

    @Test
    void aGuestWithAnEmailGetsTheEmailToo() {
        when(guests.get("g1")).thenReturn(Guest.builder().fullName("Ada Okeke").phone("+2348030000001").email("ada@guest.test").build());
        when(deliveries.send(org.mockito.ArgumentMatchers.<List<Delivery>>any())).thenAnswer(i -> i.getArgument(0));
        List<Delivery> sent = notices.confirmed(booking(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 22)));
        assertThat(sent).extracting(Delivery::channel).containsExactly(Delivery.Channel.EMAIL, Delivery.Channel.WHATSAPP);
        assertThat(sent.get(0).kind()).isEqualTo(Delivery.Kind.BOOKING_CONFIRMATION);
        assertThat(sent.get(0).subject()).isEqualTo("Your booking BK-1001 is confirmed");
        assertThat(sent.get(0).text()).contains("Reference: BK-1001").contains("Fri 20 Nov to Sun 22 Nov")
                .contains("Directions and the hotel's contact details are on your booking page: https://hospio360.com/stay/" + links.token(ID).orElseThrow());
        assertThat(sent.get(1).text()).contains("https://hospio360.com/stay/");
        assertThat(notices.cancelled(booking(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 22))).get(0).subject()).isEqualTo("Your booking BK-1001 is cancelled");
    }

    @Test
    void aHotelThatSwitchedConfirmationsOffSendsNone() {
        when(settings.forHotel(any())).thenReturn(new com.hospio.common.setup.GuestMessaging(null, false, true, true));
        assertThat(notices.confirmed(booking(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 22)))).isEmpty();
        verify(deliveries, never()).send(org.mockito.ArgumentMatchers.<List<Delivery>>any());
    }

    @Test
    void theHotelsWhatsAppIsOfferedInTheEmailAfterTheBookingPage() {
        when(settings.forHotel(any())).thenReturn(new com.hospio.common.setup.GuestMessaging("2348031110001", true, true, true));
        when(guests.get("g1")).thenReturn(Guest.builder().fullName("Ada Okeke").email("ada@guest.test").build());
        when(deliveries.send(org.mockito.ArgumentMatchers.<List<Delivery>>any())).thenAnswer(i -> i.getArgument(0));
        String text = notices.confirmed(booking(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 22))).get(0).text();
        assertThat(text).contains("Chat with the hotel on WhatsApp: https://wa.me/2348031110001?text=Hello%2C%20about%20my%20booking%20BK-1001");
        assertThat(text.indexOf("https://hospio360.com/stay/")).isLessThan(text.indexOf("https://wa.me/"));
    }

    @Test
    void theArrivalReminderCarriesTheCheckInTimeAndTheLink() {
        when(guests.get("g1")).thenReturn(Guest.builder().fullName("Ada Okeke").phone("+2348030000001").email("ada@guest.test").build());
        when(deliveries.send(org.mockito.ArgumentMatchers.<List<Delivery>>any())).thenAnswer(i -> i.getArgument(0));
        List<Delivery> sent = notices.arrivalReminder(booking(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 22)), "14:00");
        assertThat(sent).extracting(Delivery::kind).containsOnly(Delivery.Kind.ARRIVAL_REMINDER);
        assertThat(sent.get(0).text()).contains("tomorrow").contains("Check-in: Fri 20 Nov, from 14:00");
        assertThat(sent.get(1).template()).isEqualTo("hospio_checkin_reminder_v2");
        assertThat(sent.get(1).params()).containsExactly("Ada", "@hotel", "BK-1001", "14:00", "@hotelPhone", links.token(ID).orElseThrow());
    }
}

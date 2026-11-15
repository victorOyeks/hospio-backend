package com.hospio.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.setup.GuestMessaging;
import com.hospio.reservation.client.GuestMessagingSettings;
import com.hospio.reservation.client.TenantHotelClient;
import com.hospio.reservation.entity.Booking;
import com.hospio.reservation.repository.BookingRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** docs/sms-whatsapp.md: the day before arrival, once per booking, only where the hotel's switch is on. */
class ArrivalRemindersTest {

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final GuestNotices notices = mock(GuestNotices.class);
    private final GuestMessagingSettings settings = mock(GuestMessagingSettings.class);
    private final TenantHotelClient hotels = mock(TenantHotelClient.class);
    private final PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
    private final ArrivalReminders job;
    private static final LocalDate TOMORROW = LocalDate.of(2027, 3, 1);

    ArrivalRemindersTest() {
        when(txm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        job = new ArrivalReminders(bookings, notices, settings, hotels, txm);
    }

    private static Booking booking(String id, String hotel) {
        Booking b = Booking.builder().reference("BK-" + id).guestId("g1").guestName("Ada Okeke").checkInDate(TOMORROW)
                .checkOutDate(TOMORROW.plusDays(2)).nightlyRate(new BigDecimal("45000")).totalAmount(new BigDecimal("90000")).build();
        b.setId(id);
        b.setHotelId(hotel);
        b.setBusinessId("biz");
        return b;
    }

    @Test
    void eachArrivalIsToldOnceWithTheHotelsCheckInTime() {
        when(bookings.arrivalsToRemind(TOMORROW)).thenReturn(List.of(booking("b1", "h1"), booking("b2", "h1")));
        when(settings.forHotel("h1")).thenReturn(GuestMessaging.DEFAULTS);
        when(hotels.hotel("h1")).thenReturn(Optional.of(new TenantHotelClient.HotelCard("Simpas Ikeja", null, "Lagos", null, null, "14:00", "12:00")));
        when(bookings.markArrivalReminded(eq("b1"), any())).thenReturn(1);
        when(bookings.markArrivalReminded(eq("b2"), any())).thenReturn(0);   // already told by an earlier run

        assertThat(job.remind(TOMORROW)).isEqualTo(1);
        verify(notices).arrivalReminder(org.mockito.ArgumentMatchers.argThat(r -> "b1".equals(r.id())), eq("14:00"));
        verify(notices, never()).arrivalReminder(org.mockito.ArgumentMatchers.argThat(r -> "b2".equals(r.id())), anyString());
    }

    @Test
    void aHotelWithTheSwitchOffIsLeftAlone() {
        when(bookings.arrivalsToRemind(TOMORROW)).thenReturn(List.of(booking("b3", "h2")));
        when(settings.forHotel("h2")).thenReturn(new GuestMessaging(null, true, false, true));
        assertThat(job.remind(TOMORROW)).isZero();
        verify(bookings, never()).markArrivalReminded(anyString(), any());
        verify(notices, never()).arrivalReminder(any(), any());
    }
}

package com.hospio.reservation.service;

import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.reservation.client.GuestMessagingSettings;
import com.hospio.reservation.client.TenantHotelClient;
import com.hospio.reservation.dto.BookingResponse;
import com.hospio.reservation.entity.Booking;
import com.hospio.reservation.repository.BookingRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hotel setup → Guest messaging, "Send arrival-day message" (docs/sms-whatsapp.md): the day before a booking's check-in the
 * guest gets "we look forward to welcoming you tomorrow" with the check-in time and the booking page (directions), by WhatsApp
 * (hospio_checkin_reminder_v2) and email. Every hour from 09:00 to 20:00 Lagos time, so a restart doesn't lose the day; each
 * booking is claimed ({@code arrival_reminded_at}) in the same transaction that sends, so it is told once. Hotels with the
 * switch off are left alone. One instance runs it at a time (SingleInstanceScheduling).
 */
@Slf4j
@Component
public class ArrivalReminders {

    private final BookingRepository bookings;
    private final GuestNotices notices;
    private final GuestMessagingSettings settings;
    private final TenantHotelClient hotels;
    private final TransactionTemplate tx;
    private final ZoneId zone = ZoneId.of("Africa/Lagos");
    /** Today, a test seam. */
    Clock clock = Clock.systemUTC();

    public ArrivalReminders(BookingRepository bookings, GuestNotices notices, GuestMessagingSettings settings, TenantHotelClient hotels,
                            PlatformTransactionManager txm) {
        this.bookings = bookings;
        this.notices = notices;
        this.settings = settings;
        this.hotels = hotels;
        this.tx = new TransactionTemplate(txm);
    }

    @Scheduled(cron = "0 0 9-20 * * *", zone = "Africa/Lagos")
    public void run() {
        int told = remind(LocalDate.now(clock.withZone(zone)).plusDays(1));
        if (told > 0) {
            log.info("Arrival reminders: {} guests told", told);
        }
    }

    /** Tells every guest arriving on {@code day} whose hotel has the switch on; returns how many were told. */
    int remind(LocalDate day) {
        List<Booking> due = TenantContext.runWith(Tenant.system("arrival-reminders"), () -> bookings.arrivalsToRemind(day));
        Map<String, Boolean> on = new HashMap<>();
        Map<String, String> checkIn = new HashMap<>();
        int told = 0;
        for (Booking b : due) {
            String hotel = b.getHotelId();
            if (!on.computeIfAbsent(hotel, h -> settings.forHotel(h).arrivalMessage())) {
                continue;
            }
            String from = checkIn.computeIfAbsent(hotel, h -> hotels.hotel(h).map(TenantHotelClient.HotelCard::checkInTime).orElse(""));
            try {
                Boolean sent = TenantContext.runWith(Tenant.systemFor(b.getBusinessId(), hotel, null, "Arrival reminder"), () -> tx.execute(s -> {
                    if (bookings.markArrivalReminded(b.getId(), Instant.now(clock)) != 1) {
                        return false;
                    }
                    notices.arrivalReminder(BookingResponse.of(b), from.isBlank() ? null : from);
                    return true;
                }));
                told += Boolean.TRUE.equals(sent) ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("Arrival reminder for booking {} not sent: {}", b.getReference(), e.getMessage());
            }
        }
        return told;
    }
}

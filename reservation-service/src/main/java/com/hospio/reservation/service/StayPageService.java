package com.hospio.reservation.service;

import com.hospio.common.enums.BookingStatus;
import com.hospio.common.exception.GoneException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.reservation.client.TenantHotelClient;
import com.hospio.reservation.entity.Booking;
import com.hospio.reservation.repository.BookingRepository;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The guest's booking page behind the private link (docs/sms-whatsapp.md, "The guest's booking page"). No sign-in: the signed
 * token is the permission. It shows only what the guest already got in the message plus how to reach the hotel; never the
 * room number, prices, notes or anyone's phone but the hotel's. A link stops working 60 days after check-out.
 */
@Service
@RequiredArgsConstructor
public class StayPageService {

    static final int DAYS_AFTER_CHECK_OUT = 60;

    /**
     * What the page shows. {@code hotel} is null when tenant-service can't be reached. {@code hotelWhatsApp}: the hotel's own
     * WhatsApp from Hotel setup → Guest messaging, as wa.me digits ("Chat on WhatsApp"), or null. {@code brand}: the hotel's colour,
     * short name and logo (Hotel setup → Brand & colours), so the page is in the hotel's look.
     */
    public record StayPage(String reference, String status, String guestFirstName, LocalDate checkInDate, LocalDate checkOutDate,
                           int nights, int adults, int children, String roomType, String cancellationReason,
                           TenantHotelClient.HotelCard hotel, String hotelWhatsApp,
                           com.hospio.reservation.client.GuestMessagingSettings.Brand brand) {
    }

    private final StayLinks links;
    private final BookingRepository bookings;
    private final TenantHotelClient hotels;
    private final com.hospio.reservation.client.GuestMessagingSettings settings;
    /** Today, for the expiry; a test seam. */
    Clock clock = Clock.systemDefaultZone();

    @Transactional(readOnly = true)
    public StayPage page(String token) {
        String id = links.bookingId(token).orElseThrow(() -> new ResourceNotFoundException("This link is not valid"));
        // The booking may be in any hotel and nobody is signed in: read it as the system.
        Booking b = TenantContext.runWith(Tenant.system("stay-page"), () -> bookings.findById(id))
                .orElseThrow(() -> new ResourceNotFoundException("This link is not valid"));
        if (b.getCheckOutDate() != null && LocalDate.now(clock).isAfter(b.getCheckOutDate().plusDays(DAYS_AFTER_CHECK_OUT))) {
            throw new GoneException("This link has expired. Contact the hotel if you need your booking details.");
        }
        String first = b.getGuestName() == null ? null : b.getGuestName().trim().split("\\s+")[0];
        BookingStatus s = b.getStatus();
        return new StayPage(b.getReference(), s == null ? null : s.name(), first, b.getCheckInDate(), b.getCheckOutDate(), b.nights(),
                b.getAdults(), b.getChildren(), b.getRoomTypeName(), s == BookingStatus.CANCELLED ? b.getCancellationReason() : null,
                hotels.hotel(b.getHotelId()).orElse(null), settings.forHotel(b.getHotelId()).whatsappNumber(), settings.brand(b.getHotelId()));
    }
}

package com.hospio.reservation.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.reservation.dto.BookingResponse;
import com.hospio.reservation.entity.Guest;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * What the guest is told when the hotel changes their stay (Manager · Bookings, Owner portal · Bookings: "change dates,
 * guest told"): a WhatsApp message to the phone on their guest record, published for messaging-service and echoed in the
 * response on staging. Only when the dates moved; a room move on its own is not sent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuestNotices {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private final GuestService guests;
    private final DeliveryService deliveries;

    public List<Delivery> datesChanged(BookingResponse before, BookingResponse after) {
        if (Objects.equals(before.checkInDate(), after.checkInDate()) && Objects.equals(before.checkOutDate(), after.checkOutDate())) {
            return List.of();
        }
        try {
            Guest g = after.guestId() == null ? null : guests.get(after.guestId());
            if (g == null || !StringUtils.hasText(g.getPhone())) {
                return List.of();
            }
            String first = after.guestName() == null ? "" : after.guestName().trim().split("\\s+")[0];
            String text = "Hello " + first + ", your stay (" + after.reference() + ") is now " + DAY.format(after.checkInDate()) + " to "
                    + DAY.format(after.checkOutDate()) + ", " + after.nights() + (after.nights() == 1 ? " night" : " nights")
                    + (after.roomNumber() == null ? "" : ", room " + after.roomNumber()) + ". Reply to this message if that is not right.";
            return deliveries.send(new Delivery(Delivery.Channel.WHATSAPP, g.getPhone(), "Your stay has changed", text,
                    Delivery.Kind.MESSAGE, after.id()));
        } catch (RuntimeException e) {
            log.warn("Could not tell the guest about booking {}: {}", after.id(), e.getMessage());
            return List.of();
        }
    }
}

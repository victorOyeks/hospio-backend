package com.hospio.reservation.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.reservation.dto.BookingResponse;
import com.hospio.reservation.entity.Guest;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * What the guest is told about their booking: confirmed (new booking), dates changed, cancelled. An email when the guest
 * has one (sent for real through messaging-service, from the hotel's name, docs/email.md) and a WhatsApp message when they
 * have a phone (not connected yet: recorded and echoed only). Never fails the booking action.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuestNotices {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private final GuestService guests;
    private final DeliveryService deliveries;

    public List<Delivery> confirmed(BookingResponse b) {
        return tell(b, "Your booking " + b.reference() + " is confirmed", Delivery.Kind.BOOKING_CONFIRMATION, first -> "Hello " + first
                + ",\n\nYour booking is confirmed.\n\nReference: " + b.reference() + "\n" + stay(b)
                + (b.roomTypeName() == null ? "" : "\nRoom: " + b.roomTypeName() + (b.roomNumber() == null ? "" : " · " + b.roomNumber()))
                + "\n\nReply to this email if anything needs changing. We look forward to welcoming you.");
    }

    public List<Delivery> datesChanged(BookingResponse before, BookingResponse after) {
        if (Objects.equals(before.checkInDate(), after.checkInDate()) && Objects.equals(before.checkOutDate(), after.checkOutDate())) {
            return List.of();
        }
        return tell(after, "Your stay has changed", Delivery.Kind.MESSAGE, first -> "Hello " + first + ", your stay (" + after.reference()
                + ") is now " + DAY.format(after.checkInDate()) + " to " + DAY.format(after.checkOutDate()) + ", " + after.nights()
                + (after.nights() == 1 ? " night" : " nights") + (after.roomNumber() == null ? "" : ", room " + after.roomNumber())
                + ". Reply to this message if that is not right.");
    }

    public List<Delivery> cancelled(BookingResponse b) {
        return tell(b, "Your booking " + b.reference() + " is cancelled", Delivery.Kind.MESSAGE, first -> "Hello " + first
                + ", your booking " + b.reference() + " (" + stay(b).replace("\n", ", ") + ") has been cancelled."
                + (StringUtils.hasText(b.cancellationReason()) ? " Reason: " + b.cancellationReason() + "." : "")
                + " Reply to this message if you have any questions.");
    }

    private List<Delivery> tell(BookingResponse b, String subject, Delivery.Kind kind, java.util.function.Function<String, String> text) {
        try {
            Guest g = b.guestId() == null ? null : guests.get(b.guestId());
            if (g == null) {
                return List.of();
            }
            String first = b.guestName() == null ? "" : b.guestName().trim().split("\\s+")[0];
            String body = text.apply(first);
            List<Delivery> out = new ArrayList<>();
            if (StringUtils.hasText(g.getEmail())) {
                out.add(Delivery.email(g.getEmail().trim(), subject, body, kind, b.id()));
            }
            if (StringUtils.hasText(g.getPhone())) {
                out.add(Delivery.whatsApp(g.getPhone(), subject, body, kind, b.id()));
            }
            return out.isEmpty() ? List.of() : deliveries.send(out);
        } catch (RuntimeException e) {
            log.warn("Could not tell the guest about booking {}: {}", b.id(), e.getMessage());
            return List.of();
        }
    }

    private static String stay(BookingResponse b) {
        return "Stay: " + DAY.format(b.checkInDate()) + " to " + DAY.format(b.checkOutDate()) + " · " + b.nights()
                + (b.nights() == 1 ? " night" : " nights");
    }
}

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
 * have a phone: the approved hospio_booking_* template, else a one-page SMS (docs/sms-whatsapp.md). {@code @hotel} is the
 * hotel's name, filled in by messaging-service. Never fails the booking action.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuestNotices {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private final GuestService guests;
    private final DeliveryService deliveries;

    public List<Delivery> confirmed(BookingResponse b) {
        return tell(b, "Your booking " + b.reference() + " is confirmed", Delivery.Kind.BOOKING_CONFIRMATION,
                first -> new Text("hospio_booking_confirmation",
                        List.of(first, b.reference(), "@hotel", DAY.format(b.checkInDate()), DAY.format(b.checkOutDate()),
                                b.roomTypeName() == null ? "your room" : b.roomTypeName()),
                        "@hotel: Hello " + first + ", booking " + b.reference() + " is confirmed, " + DAY.format(b.checkInDate()) + " to "
                                + DAY.format(b.checkOutDate()) + ". Reply here if anything needs changing."),
                first -> "Hello " + first
                + ",\n\nYour booking is confirmed.\n\nReference: " + b.reference() + "\n" + stay(b)
                + (b.roomTypeName() == null ? "" : "\nRoom: " + b.roomTypeName() + (b.roomNumber() == null ? "" : " · " + b.roomNumber()))
                + "\n\nReply to this email if anything needs changing. We look forward to welcoming you.");
    }

    public List<Delivery> datesChanged(BookingResponse before, BookingResponse after) {
        if (Objects.equals(before.checkInDate(), after.checkInDate()) && Objects.equals(before.checkOutDate(), after.checkOutDate())) {
            return List.of();
        }
        return tell(after, "Your stay has changed", Delivery.Kind.BOOKING_CHANGED,
                first -> new Text("hospio_booking_changed",
                        List.of(first, after.reference(), "@hotel", DAY.format(after.checkInDate()), DAY.format(after.checkOutDate())),
                        "@hotel: Hello " + first + ", booking " + after.reference() + " is now " + DAY.format(after.checkInDate()) + " to "
                                + DAY.format(after.checkOutDate()) + ". Reply here if that is not right."),
                // "Label: value" lines become the email's details card (docs/email.md).
                first -> "Hello " + first + ",\n\nYour stay has new dates.\n\nReference: " + after.reference()
                        + "\nCheck-in: " + DAY.format(after.checkInDate()) + "\nCheck-out: " + DAY.format(after.checkOutDate())
                        + "\nNights: " + after.nights() + (after.roomNumber() == null ? "" : "\nRoom: " + after.roomNumber())
                        + "\n\nReply to this email if that is not right.");
    }

    public List<Delivery> cancelled(BookingResponse b) {
        String why = StringUtils.hasText(b.cancellationReason()) ? "Reason: " + b.cancellationReason().trim().replaceAll("[.\\s]+$", "") + "."
                : "We hope to welcome you another time.";
        return tell(b, "Your booking " + b.reference() + " is cancelled", Delivery.Kind.BOOKING_CANCELLED,
                first -> new Text("hospio_booking_cancelled", List.of(first, b.reference(), "@hotel", why),
                        "@hotel: Hello " + first + ", booking " + b.reference() + " has been cancelled. " + why),
                first -> "Hello " + first + ",\n\nYour booking has been cancelled.\n\nReference: " + b.reference()
                        + "\nWas: " + DAY.format(b.checkInDate()) + " to " + DAY.format(b.checkOutDate())
                        + (StringUtils.hasText(b.cancellationReason()) ? "\nReason: " + b.cancellationReason().trim() : "")
                        + "\n\nReply to this email if you have any questions. We hope to welcome you another time.");
    }

    /** The WhatsApp side: its template and values, and the one-page SMS it falls back to. */
    record Text(String template, List<String> params, String sms) {
    }

    private List<Delivery> tell(BookingResponse b, String subject, Delivery.Kind kind, java.util.function.Function<String, Text> whatsApp,
                                java.util.function.Function<String, String> text) {
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
                Text w = whatsApp.apply(first.isBlank() ? "there" : first);
                out.add(Delivery.whatsApp(g.getPhone(), subject, w.sms(), kind, b.id()).withTemplate(w.template(), w.params()));
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

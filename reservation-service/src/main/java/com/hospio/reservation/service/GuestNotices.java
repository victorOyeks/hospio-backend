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
 * What the guest is told about their booking: confirmed (new booking, unless Hotel setup → Guest messaging turns it off), dates
 * changed, cancelled, and the day before arrival ({@link ArrivalReminders}). An email when the guest has one (sent for real
 * through messaging-service, from the hotel's name, docs/email.md) and a WhatsApp message when they have a phone: the
 * approved hospio_* template, never an SMS (docs/sms-whatsapp.md). The hotel's own WhatsApp number, when set, is offered in
 * the email as "Chat with the hotel on WhatsApp". {@code @hotel} is the
 * hotel's name and {@code @hotelPhone} its phone, filled in by messaging-service. The template's last value is the booking page
 * link's ending ({@link StayLinks}), for its "View booking" button. Never fails the booking action.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuestNotices {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private final GuestService guests;
    private final DeliveryService deliveries;
    private final StayLinks stayLinks;
    private final com.hospio.reservation.client.GuestMessagingSettings settings;

    public List<Delivery> confirmed(BookingResponse b) {
        if (!settings.forHotel(b.hotelId()).sendConfirmation()) {
            log.info("Booking {}: confirmation not sent (switched off in Hotel setup → Guest messaging)", b.reference());
            return List.of();
        }
        return tell(b, "Your booking " + b.reference() + " is confirmed", Delivery.Kind.BOOKING_CONFIRMATION,
                first -> new Text("hospio_booking_confirmation_v2",
                        List.of(first, "@hotel", b.reference(), DAY.format(b.checkInDate()), DAY.format(b.checkOutDate()),
                                b.roomTypeName() == null ? "To be assigned" : b.roomTypeName(), "@hotelPhone"),
                        "@hotel: Hello " + first + ", booking " + b.reference() + " is confirmed, " + DAY.format(b.checkInDate()) + " to "
                                + DAY.format(b.checkOutDate()) + "."),
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
                first -> new Text("hospio_booking_changed_v2",
                        List.of(first, "@hotel", after.reference(), DAY.format(after.checkInDate()), DAY.format(after.checkOutDate()), "@hotelPhone"),
                        "@hotel: Hello " + first + ", booking " + after.reference() + " is now " + DAY.format(after.checkInDate()) + " to "
                                + DAY.format(after.checkOutDate()) + "."),
                // "Label: value" lines become the email's details card (docs/email.md).
                first -> "Hello " + first + ",\n\nYour stay has new dates.\n\nReference: " + after.reference()
                        + "\nCheck-in: " + DAY.format(after.checkInDate()) + "\nCheck-out: " + DAY.format(after.checkOutDate())
                        + "\nNights: " + after.nights() + (after.roomNumber() == null ? "" : "\nRoom: " + after.roomNumber())
                        + "\n\nReply to this email if that is not right.");
    }

    public List<Delivery> cancelled(BookingResponse b) {
        String why = StringUtils.hasText(b.cancellationReason()) ? b.cancellationReason().trim().replaceAll("[.\\s]+$", "")
                : "Not given";
        return tell(b, "Your booking " + b.reference() + " is cancelled", Delivery.Kind.BOOKING_CANCELLED,
                first -> new Text("hospio_booking_cancelled_v2",
                        List.of(first, "@hotel", b.reference(), DAY.format(b.checkInDate()) + " to " + DAY.format(b.checkOutDate()), why, "@hotelPhone"),
                        "@hotel: Hello " + first + ", booking " + b.reference() + " has been cancelled."),
                first -> "Hello " + first + ",\n\nYour booking has been cancelled.\n\nReference: " + b.reference()
                        + "\nWas: " + DAY.format(b.checkInDate()) + " to " + DAY.format(b.checkOutDate())
                        + (StringUtils.hasText(b.cancellationReason()) ? "\nReason: " + b.cancellationReason().trim() : "")
                        + "\n\nReply to this email if you have any questions. We hope to welcome you another time.");
    }

    /**
     * The day before arrival: "we look forward to welcoming you tomorrow", the check-in time ({@code checkInFrom}, e.g.
     * "14:00", or null) and the booking page with directions. Sent by {@link ArrivalReminders} when the hotel's switch is on.
     */
    public List<Delivery> arrivalReminder(BookingResponse b, String checkInFrom) {
        String from = StringUtils.hasText(checkInFrom) ? checkInFrom : "the hotel's check-in time";
        return tell(b, "See you tomorrow", Delivery.Kind.ARRIVAL_REMINDER,
                first -> new Text("hospio_checkin_reminder_v2", List.of(first, "@hotel", b.reference(), from, "@hotelPhone"),
                        "@hotel: Hello " + first + ", we look forward to welcoming you tomorrow. Booking " + b.reference() + "."),
                first -> "Hello " + first + ",\n\nWe look forward to welcoming you tomorrow.\n\nReference: " + b.reference()
                        + "\nCheck-in: " + DAY.format(b.checkInDate()) + (StringUtils.hasText(checkInFrom) ? ", from " + checkInFrom : "")
                        + "\nCheck-out: " + DAY.format(b.checkOutDate()) + "\nNights: " + b.nights()
                        + "\n\nReply to this email if your plans have changed. See you soon.");
    }

    /** The WhatsApp side: its template and values, and the text kept on the delivery record. */
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
            // The guest's booking page (dates, the hotel's address with directions, its phone) goes in every channel: the email's
            // button, the SMS's link and WhatsApp's View booking button. A booking without a link (id not a UUID) goes without.
            java.util.Optional<String> token = stayLinks.token(b.id());
            java.util.Optional<String> url = stayLinks.url(b.id());
            // The hotel's own WhatsApp (Hotel setup → Guest messaging): a wa.me chat with the booking reference typed in. After the
            // booking page, so the page stays the email's button.
            String hotelWhatsApp = settings.forHotel(b.hotelId()).whatsappNumber();
            String chat = hotelWhatsApp == null ? "" : "\n\nChat with the hotel on WhatsApp: https://wa.me/" + hotelWhatsApp + "?text="
                    + java.net.URLEncoder.encode("Hello, about my booking " + b.reference(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            String body = text.apply(first)
                    + url.map(u -> "\n\nDirections and the hotel's contact details are on your booking page: " + u).orElse("") + chat;
            List<Delivery> out = new ArrayList<>();
            if (StringUtils.hasText(g.getEmail())) {
                out.add(Delivery.email(g.getEmail().trim(), subject, body, kind, b.id()));
            }
            if (StringUtils.hasText(g.getPhone())) {
                Text w = whatsApp.apply(first.isBlank() ? "there" : first);
                // The template's "View booking" button: its link's ending is the template's last value (docs/sms-whatsapp.md).
                // The SMS it falls back to ends with the whole link (SmsText.fit never cuts a link; words before it go first).
                String sms = w.sms() + url.map(u -> " Details and directions: " + u).orElse("");
                Delivery d = Delivery.whatsApp(g.getPhone(), subject, sms, kind, b.id());
                if (token.isPresent()) {
                    List<String> values = new ArrayList<>(w.params());
                    values.add(token.get());
                    d = d.withTemplate(w.template(), values);
                }
                out.add(d);
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

package com.hospio.common.delivery;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The one list of messages that can go on WhatsApp (docs/sms-whatsapp.md, "Templates in use"): WhatsApp carries only a template
 * Meta approved, so a message goes on WhatsApp only when its kind has one here. Everything else (stay summary, folio, a typed
 * message to a guest, staff notices) has no template and goes by email; a WhatsApp asked for one of those is never queued
 * (messaging-service records it SKIPPED with the reason, the screens that would send one refuse it, and {@code GET
 * /messaging/channels} gives the app {@code whatsappKinds} from this list and the approvals, so it only offers WhatsApp where it works).
 */
public final class WhatsAppTemplates {

    public static final String BOOKING_CONFIRMATION = "hospio_booking_confirmation_v2";
    public static final String BOOKING_CHANGED = "hospio_booking_changed_v2";
    public static final String BOOKING_CANCELLED = "hospio_booking_cancelled_v2";
    public static final String ARRIVAL_REMINDER = "hospio_checkin_reminder_v2";
    public static final String RECEIPT = "hospio_payment_receipt_v2";
    /** The authentication template for a phone code ("Send on WhatsApp instead"). */
    public static final String PHONE_CODE = "oyeks_otp";

    private static final Map<String, String> BY_KIND;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(Delivery.Kind.BOOKING_CONFIRMATION.name(), BOOKING_CONFIRMATION);
        m.put(Delivery.Kind.BOOKING_CHANGED.name(), BOOKING_CHANGED);
        m.put(Delivery.Kind.BOOKING_CANCELLED.name(), BOOKING_CANCELLED);
        m.put(Delivery.Kind.ARRIVAL_REMINDER.name(), ARRIVAL_REMINDER);
        m.put(Delivery.Kind.RECEIPT.name(), RECEIPT);
        m.put(Delivery.Kind.PHONE_CODE.name(), PHONE_CODE);
        BY_KIND = Collections.unmodifiableMap(m);
    }

    private WhatsAppTemplates() {
    }

    /** Kind → its WhatsApp template, in a stable order. */
    public static Map<String, String> byKind() {
        return BY_KIND;
    }

    /** The template a kind goes as on WhatsApp, or null when it has none (it then never goes on WhatsApp). */
    public static String forKind(String kind) {
        return kind == null ? null : BY_KIND.get(kind.trim().toUpperCase(Locale.ROOT));
    }

    /** Whether this kind of message can go on WhatsApp at all (it has an approved-template slot). */
    public static boolean capable(String kind) {
        return forKind(kind) != null;
    }

    public static boolean capable(Delivery.Kind kind) {
        return kind != null && capable(kind.name());
    }

    /** What a WhatsApp of a kind without a template is recorded with (SKIPPED, never FAILED). */
    public static final String NO_TEMPLATE = "Not sent: WhatsApp has no approved template for this message; it goes by email";
}

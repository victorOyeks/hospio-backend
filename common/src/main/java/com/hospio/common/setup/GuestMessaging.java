package com.hospio.common.setup;

import java.util.Optional;

/**
 * Hotel setup → Guest messaging (SetupCatalogue MESSAGING), as the senders read it (docs/sms-whatsapp.md):
 * {@code whatsappNumber} is the hotel's own WhatsApp, where guests chat with the hotel ("Chat on WhatsApp" on the booking
 * page, a line in the emails); the switches turn the booking confirmation, the day-before-arrival message and automatic
 * receipts on or off. Every switch is on when hotel-config can't be read or leaves it out, as the catalogue's defaults are.
 */
public record GuestMessaging(String whatsappNumber, boolean sendConfirmation, boolean arrivalMessage, boolean sendReceipts) {

    public static final GuestMessaging DEFAULTS = new GuestMessaging(null, true, true, true);

    public static GuestMessaging of(Optional<SetupSection.Messaging> section) {
        return section.map(m -> new GuestMessaging(whatsApp(m.whatsappNumber()), on(m.sendConfirmation()), on(m.arrivalMessage()),
                on(m.sendReceipts()))).orElse(DEFAULTS);
    }

    private static boolean on(Boolean value) {
        return value == null || value;
    }

    /**
     * The number as wa.me wants it: digits with the country code ("0803 111 0001" → 2348031110001), or null when it isn't a
     * phone number at all.
     */
    public static String whatsApp(String raw) {
        if (raw == null) {
            return null;
        }
        String d = raw.replaceAll("[^0-9]", "");
        if (d.startsWith("00")) {
            d = d.substring(2);
        }
        if (d.length() == 11 && d.startsWith("0")) {
            d = "234" + d.substring(1);
        } else if (d.length() == 10 && d.matches("^[789].*")) {
            d = "234" + d;
        }
        return d.length() >= 10 && d.length() <= 15 ? d : null;
    }

    /** "+234 803 111 0001" for showing in a message, from the wa.me digits. */
    public static String shown(String digits) {
        if (digits == null) {
            return null;
        }
        if (digits.startsWith("234") && digits.length() == 13) {
            return "+234 " + digits.substring(3, 6) + " " + digits.substring(6, 9) + " " + digits.substring(9);
        }
        return "+" + digits;
    }
}

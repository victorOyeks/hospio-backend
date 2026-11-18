package com.hospio.common.venue;

import java.util.Locale;

/**
 * What kind of place a location (tenant-service's {@code hotels} row) is (docs/plan-venue-type.md). The type is a label and the
 * default for new settings; features key off the capability, never the label: {@link #hasAccommodation} is true for a HOTEL and for
 * any location that has rooms, so a lounge that later adds rooms gets bookings, folios and charge-to-room.
 */
public enum VenueType {
    HOTEL, LOUNGE, BAR, RESTAURANT, CLUB;

    /** Locations saved before venue types existed, and anything unreadable, are hotels. */
    public static VenueType parse(String value) {
        if (value == null || value.isBlank()) {
            return HOTEL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return HOTEL;
        }
    }

    /** The accommodation capability: rooms, stays, bookings, folios, night-audit arrival checks and charge-to-room. */
    public static boolean hasAccommodation(VenueType type, int roomCount) {
        return type == null || type == HOTEL || roomCount > 0;
    }

    public static boolean hasAccommodation(String type, int roomCount) {
        return hasAccommodation(parse(type), roomCount);
    }

    /** How people read it: "Hotel", "Lounge". */
    public String label() {
        return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }
}

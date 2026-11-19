package com.hospio.common.venue;

import java.util.Collection;
import java.util.Locale;

/**
 * The words for a place (docs/plan-venue-type.md, phase 5): a lounge is never told about "this hotel". Pure wording, no lookups; the
 * hotel app mirrors it ({@code packages/api/src/venue.ts}). A HOTEL, and anything unknown, reads exactly as before venue types:
 * "hotel", "hotels", "Hotel setup".
 *
 * <p>For one location use {@link #of(VenueType)}; for a whole business (its owner's "your hotels") use {@link #ofBusiness}: "hotel"
 * while every location is a hotel, "location" as soon as one is not. {@link VenuePlaces} finds the type for a hotel id.
 */
public record VenueWording(String place) {

    public static final VenueWording HOTEL = new VenueWording("hotel");
    /** A business with a lounge, bar or restaurant among its locations, or a place whose kind is not known to be a hotel. */
    public static final VenueWording LOCATION = new VenueWording("location");

    /** "hotel", "lounge", "bar", "restaurant", "club"; a null type is a hotel. */
    public static VenueWording of(VenueType type) {
        return type == null || type == VenueType.HOTEL ? HOTEL : new VenueWording(type.name().toLowerCase(Locale.ROOT));
    }

    public static VenueWording of(String type) {
        return of(VenueType.parse(type));
    }

    /**
     * Business level: {@code kind} is the business's venueKind (HOTEL … or MIXED), {@code types} the types of its locations (may be
     * null or empty: then the kind decides). Hotels only reads "hotel"; anything else "location".
     */
    public static VenueWording ofBusiness(String kind, Collection<String> types) {
        if (types != null && !types.isEmpty()) {
            return types.stream().allMatch(t -> VenueType.parse(t) == VenueType.HOTEL) ? HOTEL : LOCATION;
        }
        return kind == null || kind.isBlank() || "HOTEL".equalsIgnoreCase(kind.trim()) ? HOTEL : LOCATION;
    }

    public boolean isHotel() {
        return "hotel".equals(place);
    }

    /** "hotels", "lounges", "locations". */
    public String places() {
        return place + "s";
    }

    /** "Hotel", "Lounge". */
    public String capital() {
        return Character.toUpperCase(place.charAt(0)) + place.substring(1);
    }

    /** "Hotels", "Locations". */
    public String capitalPlaces() {
        return capital() + "s";
    }

    /** "a hotel", "a lounge". */
    public String a() {
        return "a " + place;
    }

    /** "hotel's", "lounge's". */
    public String possessive() {
        return place + "'s";
    }

    /** The settings area's name: "Hotel setup" for a hotel, "Lounge setup", "Restaurant setup"; "Setup" at business level. */
    public String setup() {
        return "location".equals(place) ? "Setup" : capital() + " setup";
    }
}

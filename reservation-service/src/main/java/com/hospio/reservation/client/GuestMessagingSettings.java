package com.hospio.reservation.client;

import com.hospio.common.security.JwtUtil;
import com.hospio.common.setup.GuestMessaging;
import com.hospio.common.setup.HotelSettingsReader;
import com.hospio.common.setup.SetupSection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Hotel setup → Guest messaging for a hotel (cached 60 s by the shared reader); every switch on when hotel-config can't be read. */
@Component
public class GuestMessagingSettings {

    private final HotelSettingsReader settings;

    public GuestMessagingSettings(@Value("${services.hotelconfig.url}") String url, JwtUtil jwtUtil,
                                  @Value("${services.internal.api-key}") String internalApiKey) {
        this.settings = new HotelSettingsReader(url, jwtUtil, internalApiKey);
    }

    /**
     * Hotel setup → Brand & colours for the guest's booking page: colour (#rrggbb), short name and https logo, each null when
     * not set or unreadable (the page then keeps Hospio360's look).
     */
    public record Brand(String colour, String shortName, String logoUrl) {
        public static final Brand NONE = new Brand(null, null, null);
    }

    public Brand brand(String hotelId) {
        if (hotelId == null) {
            return Brand.NONE;
        }
        return settings.section(hotelId, SetupSection.Brand.class).map(b -> {
            String c = text(b.primaryColour()).toLowerCase();
            String name = text(b.shortName());
            String logo = text(b.logoUrl());
            return new Brand(c.matches("^#[0-9a-f]{6}$") ? c : null, name.isEmpty() ? null : name.length() > 24 ? name.substring(0, 24) : name,
                    logo.matches("^https://\\S+$") ? logo : null);
        }).orElse(Brand.NONE);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    public GuestMessaging forHotel(String hotelId) {
        return hotelId == null ? GuestMessaging.DEFAULTS : GuestMessaging.of(settings.section(hotelId, SetupSection.Messaging.class));
    }
}

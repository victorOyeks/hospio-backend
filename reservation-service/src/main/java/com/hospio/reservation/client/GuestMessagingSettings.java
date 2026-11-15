package com.hospio.reservation.client;

import com.hospio.common.security.JwtUtil;
import com.hospio.common.setup.GuestMessaging;
import com.hospio.common.setup.HotelSettingsReader;
import com.hospio.common.setup.SetupCatalogue;
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

    public GuestMessaging forHotel(String hotelId) {
        return hotelId == null ? GuestMessaging.DEFAULTS : GuestMessaging.of(settings.section(hotelId, SetupCatalogue.MESSAGING));
    }
}

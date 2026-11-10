package com.hospio.housekeeping.client;

import com.hospio.common.security.JwtUtil;
import com.hospio.common.setup.HotelSettingsReader;
import com.hospio.common.setup.SetupCatalogue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Hotel setup → Housekeeping: how many days a found item is held before it may be disposed of (90 when unset or unreachable). */
@Component
public class HotelConfigClient {

    public static final int DEFAULT_HOLD_DAYS = 90;

    private final HotelSettingsReader settings;

    public HotelConfigClient(@Value("${services.hotelconfig.url}") String url, JwtUtil jwtUtil,
                             @Value("${services.internal.api-key}") String internalApiKey) {
        this.settings = new HotelSettingsReader(url, jwtUtil, internalApiKey);
    }

    public int lostFoundHoldDays(String hotelId) {
        return settings.section(hotelId, SetupCatalogue.HOUSEKEEPING)
                .map(n -> n.path("lostFoundHoldDays").isNumber() ? Math.max(0, n.path("lostFoundHoldDays").asInt()) : DEFAULT_HOLD_DAYS)
                .orElse(DEFAULT_HOLD_DAYS);
    }
}

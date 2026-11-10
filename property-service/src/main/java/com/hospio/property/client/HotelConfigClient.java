package com.hospio.property.client;

import com.hospio.common.security.JwtUtil;
import com.hospio.common.setup.HotelSettingsReader;
import com.hospio.common.setup.SetupCatalogue;
import com.hospio.property.service.RoomPriceRule;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Hotel setup → Approval limits: whether a manager's room price change waits for the owner, and above what change. */
@Component
public class HotelConfigClient {

    private final HotelSettingsReader settings;

    public HotelConfigClient(@Value("${services.hotelconfig.url}") String url, JwtUtil jwtUtil,
                             @Value("${services.internal.api-key}") String internalApiKey) {
        this.settings = new HotelSettingsReader(url, jwtUtil, internalApiKey);
    }

    /**
     * {@code rateChangesNeedOwner} and {@code rateChangeOwnerPct} (0 = every change). When hotel-config can't be read the safe
     * answer is used: every manager's price change waits for the owner.
     */
    public RoomPriceRule.Policy roomPricePolicy(String hotelId) {
        return settings.section(hotelId, SetupCatalogue.APPROVAL_LIMITS)
                .map(n -> new RoomPriceRule.Policy(n.path("rateChangesNeedOwner").asBoolean(true),
                        n.path("rateChangeOwnerPct").isNumber() ? n.path("rateChangeOwnerPct").decimalValue() : BigDecimal.ZERO))
                .orElse(new RoomPriceRule.Policy(true, BigDecimal.ZERO));
    }
}

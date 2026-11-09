package com.hospio.fnb.client;

import com.hospio.common.security.JwtUtil;
import com.hospio.common.setup.HotelSettingsReader;
import com.hospio.common.setup.SetupCatalogue;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Hotel setup for the outlets: OUTLETS (roomCharge, tabs, tabLimit) and TAXES_CURRENCY (pricesIncludeTax, vatPct), read from
 * hotel-config and cached 60 s. When hotel-config can't be reached the catalogue defaults apply.
 */
@Component
public class OutletSettings {

    public record Settings(boolean roomCharge, boolean tabs, BigDecimal tabLimit, boolean pricesIncludeTax, BigDecimal vatPct) {
        public static Settings defaults() {
            return new Settings(true, true, BigDecimal.valueOf(50000), true, new BigDecimal("7.5"));
        }
    }

    private final HotelSettingsReader reader;

    public OutletSettings(@Value("${services.hotelconfig.url}") String url, JwtUtil jwtUtil, @Value("${services.internal.api-key}") String key) {
        this.reader = new HotelSettingsReader(url, jwtUtil, key);
    }

    public Settings get(String hotelId) {
        Settings d = Settings.defaults();
        var outlets = reader.section(hotelId, SetupCatalogue.OUTLETS);
        var taxes = reader.section(hotelId, SetupCatalogue.TAXES_CURRENCY);
        return new Settings(
                outlets.map(n -> n.path("roomCharge").asBoolean(d.roomCharge())).orElse(d.roomCharge()),
                outlets.map(n -> n.path("tabs").asBoolean(d.tabs())).orElse(d.tabs()),
                outlets.map(n -> n.path("tabLimit")).filter(n -> n.isNumber()).map(n -> n.decimalValue()).orElse(d.tabLimit()),
                taxes.map(n -> n.path("pricesIncludeTax").asBoolean(d.pricesIncludeTax())).orElse(d.pricesIncludeTax()),
                taxes.map(n -> n.path("vatPct")).filter(n -> n.isNumber()).map(n -> n.decimalValue()).orElse(d.vatPct()));
    }
}

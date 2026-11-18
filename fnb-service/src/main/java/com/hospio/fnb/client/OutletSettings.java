package com.hospio.fnb.client;

import com.hospio.common.security.JwtUtil;
import com.hospio.common.setup.HotelSettingsReader;
import com.hospio.common.setup.SetupSection;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Hotel setup for the outlets: OUTLETS (roomCharge, tabs, tabLimit) and TAXES_CURRENCY (pricesIncludeTax, vatPct), read from
 * hotel-config and cached 60 s. When hotel-config can't be reached the catalogue defaults apply.
 */
@Component
public class OutletSettings {

    /** {@code serviceChargePct}: Hospio's fee passed to guests ("Service charge (x%)"), 0 when the hotel absorbs it. */
    public record Settings(boolean roomCharge, boolean tabs, BigDecimal tabLimit, boolean pricesIncludeTax, BigDecimal vatPct, BigDecimal serviceChargePct,
                           boolean tabNameRequired, boolean waitersTakePayment, int tabMaxHours, java.util.Set<String> discountRoles,
                           BigDecimal discountLimitPct, boolean recordTips) {
        public Settings(boolean roomCharge, boolean tabs, BigDecimal tabLimit, boolean pricesIncludeTax, BigDecimal vatPct, BigDecimal serviceChargePct,
                        boolean tabNameRequired, boolean waitersTakePayment, int tabMaxHours) {
            this(roomCharge, tabs, tabLimit, pricesIncludeTax, vatPct, serviceChargePct, tabNameRequired, waitersTakePayment, tabMaxHours,
                    roles(SetupSection.Outlets.DEFAULT_DISCOUNT_ROLES), BigDecimal.TEN, false);
        }

        public Settings(boolean roomCharge, boolean tabs, BigDecimal tabLimit, boolean pricesIncludeTax, BigDecimal vatPct, BigDecimal serviceChargePct,
                        boolean tabNameRequired, boolean waitersTakePayment) {
            this(roomCharge, tabs, tabLimit, pricesIncludeTax, vatPct, serviceChargePct, tabNameRequired, waitersTakePayment, 4);
        }

        public Settings withDiscounts(java.util.Set<String> roles, BigDecimal limitPct) {
            return new Settings(roomCharge, tabs, tabLimit, pricesIncludeTax, vatPct, serviceChargePct, tabNameRequired, waitersTakePayment, tabMaxHours,
                    roles, limitPct, recordTips);
        }

        public Settings withTips(boolean on) {
            return new Settings(roomCharge, tabs, tabLimit, pricesIncludeTax, vatPct, serviceChargePct, tabNameRequired, waitersTakePayment, tabMaxHours,
                    discountRoles, discountLimitPct, on);
        }

        public Settings(boolean roomCharge, boolean tabs, BigDecimal tabLimit, boolean pricesIncludeTax, BigDecimal vatPct) {
            this(roomCharge, tabs, tabLimit, pricesIncludeTax, vatPct, BigDecimal.ZERO);
        }

        public Settings(boolean roomCharge, boolean tabs, BigDecimal tabLimit, boolean pricesIncludeTax, BigDecimal vatPct, BigDecimal serviceChargePct) {
            this(roomCharge, tabs, tabLimit, pricesIncludeTax, vatPct, serviceChargePct, false, false);
        }

        public static Settings defaults() {
            return new Settings(true, true, BigDecimal.valueOf(50000), true, new BigDecimal("7.5"));
        }
    }

    /** "CASHIER, bar" → {CASHIER, BAR}; an empty string is nobody (only managers give discounts without asking). */
    public static java.util.Set<String> roles(String commaSeparated) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (commaSeparated != null) {
            for (String r : commaSeparated.split(",")) {
                if (!r.isBlank()) {
                    out.add(r.trim().toUpperCase(java.util.Locale.ROOT));
                }
            }
        }
        return out;
    }

    private final HotelSettingsReader reader;

    public OutletSettings(@Value("${services.hotelconfig.url}") String url, JwtUtil jwtUtil, @Value("${services.internal.api-key}") String key) {
        this.reader = new HotelSettingsReader(url, jwtUtil, key);
    }

    public Settings get(String hotelId) {
        Settings d = Settings.defaults();
        var outlets = reader.section(hotelId, SetupSection.Outlets.class);
        var taxes = reader.section(hotelId, SetupSection.TaxesCurrency.class);
        return new Settings(
                outlets.map(SetupSection.Outlets::roomCharge).orElse(d.roomCharge()),
                outlets.map(SetupSection.Outlets::tabs).orElse(d.tabs()),
                outlets.map(SetupSection.Outlets::tabLimit).orElse(d.tabLimit()),
                taxes.map(SetupSection.TaxesCurrency::pricesIncludeTax).orElse(d.pricesIncludeTax()),
                taxes.map(SetupSection.TaxesCurrency::vatPct).orElse(d.vatPct()),
                com.hospio.common.billing.GuestServiceCharge.rate(reader.section(hotelId, SetupSection.Payments.class).orElse(null)),
                outlets.map(SetupSection.Outlets::tabNameRequired).orElse(false),
                outlets.map(SetupSection.Outlets::waitersTakePayment).orElse(false),
                Math.max(1, Math.min(72, outlets.map(SetupSection.Outlets::tabMaxHours).orElse(4))),
                roles(outlets.map(SetupSection.Outlets::discountRoles).orElse(SetupSection.Outlets.DEFAULT_DISCOUNT_ROLES)),
                reader.section(hotelId, SetupSection.ApprovalLimits.class).map(SetupSection.ApprovalLimits::discountPct).orElse(BigDecimal.TEN),
                outlets.map(SetupSection.Outlets::recordTips).orElse(false));
    }
}

package com.hospio.platform.settings;

import java.math.BigDecimal;
import java.util.Map;

/** The fees group, typed. Built from the merged settings map so a missing or malformed value falls back to its default. */
public record FeeSettings(String model, BigDecimal perRoom, BigDecimal revenuePct, int trialDays, int dueDay, int graceDays,
                          BigDecimal marketerSharePct, int marketerMonths, BigDecimal whtPct, BigDecimal providerFeePct,
                          BigDecimal hmsSharePct) {

    public static FeeSettings from(Map<String, Object> fees) {
        Map<String, Object> d = PlatformSettingsCatalogue.defaults(PlatformSettingsCatalogue.FEES);
        return new FeeSettings(
                text(fees, d, "model"),
                number(fees, d, "perRoom"), number(fees, d, "revenuePct"),
                number(fees, d, "trialDays").intValue(), number(fees, d, "dueDay").intValue(), number(fees, d, "graceDays").intValue(),
                number(fees, d, "marketerSharePct"), number(fees, d, "marketerMonths").intValue(), number(fees, d, "whtPct"),
                number(fees, d, "providerFeePct"), number(fees, d, "hmsSharePct"));
    }

    public static FeeSettings defaults() {
        return from(Map.of());
    }

    private static String text(Map<String, Object> m, Map<String, Object> d, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? String.valueOf(d.get(key)) : String.valueOf(v);
    }

    private static BigDecimal number(Map<String, Object> m, Map<String, Object> d, String key) {
        Object v = m == null ? null : m.get(key);
        BigDecimal parsed = parse(v);
        return parsed == null ? parse(d.get(key)) : parsed;
    }

    private static BigDecimal parse(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

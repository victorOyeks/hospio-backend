package com.hospio.platform.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The platform settings and their defaults, by group. What the admin saves is laid over these, so a fresh
 * environment answers fully. Fee percentages are percents (2 means 2%), amounts are naira.
 */
public final class PlatformSettingsCatalogue {

    public static final String GENERAL = "general";
    public static final String FEES = "fees";
    public static final String SECURITY = "security";

    public static final List<String> GROUPS = List.of(GENERAL, FEES, SECURITY);

    private PlatformSettingsCatalogue() {
    }

    public static Map<String, Map<String, Object>> defaults() {
        Map<String, Map<String, Object>> all = new LinkedHashMap<>();
        Map<String, Object> general = new LinkedHashMap<>();
        general.put("company", "Hospio360");
        general.put("supportEmail", "support@hospio360.com");
        general.put("supportWhatsApp", "+2348000000000");
        general.put("website", "https://hospio360.com");
        general.put("timezone", "Africa/Lagos");
        all.put(GENERAL, general);

        Map<String, Object> fees = new LinkedHashMap<>();
        fees.put("model", "PERCENT_OF_PAYMENTS");
        fees.put("perRoom", 1500);
        fees.put("revenuePct", 2);
        fees.put("trialDays", 30);
        fees.put("dueDay", 5);
        fees.put("graceDays", 10);
        fees.put("marketerSharePct", 20);
        fees.put("marketerMonths", 24);
        fees.put("whtPct", 5);
        fees.put("providerFeePct", 0.5);
        fees.put("hmsSharePct", 1.5);
        all.put(FEES, fees);

        Map<String, Object> security = new LinkedHashMap<>();
        security.put("require2Step", false);
        security.put("sessionHours", 12);
        security.put("adminIpAllowlist", false);
        security.put("auditRetentionDays", 365);
        all.put(SECURITY, security);
        return all;
    }

    public static Map<String, Object> defaults(String group) {
        Map<String, Object> d = defaults().get(group);
        return d == null ? Map.of() : d;
    }

    public static boolean isGroup(String group) {
        return group != null && GROUPS.contains(group);
    }
}

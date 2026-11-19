package com.hospio.platform.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The platform settings groups and their defaults. What the admin saves is laid over these, key by key, so a fresh
 * environment answers fully. Each group is a record ({@link GeneralSettings}, {@link FeeSettings}, {@link SecuritySettings});
 * each of its fields is stored as one row (group, key = field name, value = the field as JSON).
 */
public final class PlatformSettingsCatalogue {

    public static final String GENERAL = "general";
    public static final String FEES = "fees";
    public static final String SECURITY = "security";
    /** System health alert limits (docs/system-health.md). */
    public static final String SYSTEM = "system";

    public static final List<String> GROUPS = List.of(GENERAL, FEES, SECURITY, SYSTEM);

    private PlatformSettingsCatalogue() {
    }

    /** The defaults of every group, in order. */
    public static Map<String, Record> defaults() {
        Map<String, Record> all = new LinkedHashMap<>();
        all.put(GENERAL, GeneralSettings.DEFAULTS);
        all.put(FEES, FeeSettings.DEFAULTS);
        all.put(SECURITY, SecuritySettings.DEFAULTS);
        all.put(SYSTEM, SystemHealthSettings.DEFAULTS);
        return all;
    }

    public static boolean isGroup(String group) {
        return group != null && GROUPS.contains(group);
    }
}

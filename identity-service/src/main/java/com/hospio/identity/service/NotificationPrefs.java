package com.hospio.identity.service;

import com.hospio.common.exception.BadRequestException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Email on or off per category (docs/profile.md). Kept as the list of categories turned off, so a new category starts on
 * for everyone. In-app notices are always on.
 */
public final class NotificationPrefs {

    public static final List<String> CATEGORIES = List.of("bookings", "approvals", "money", "stock", "maintenance", "staff", "reports");

    private NotificationPrefs() {
    }

    /** Every category with true (send) or false (turned off). */
    public static Map<String, Boolean> email(String emailOff) {
        Set<String> off = parse(emailOff);
        Map<String, Boolean> out = new LinkedHashMap<>();
        CATEGORIES.forEach(c -> out.put(c, !off.contains(c)));
        return out;
    }

    /** The stored value after applying {@code changes} (unknown categories are refused). */
    public static String apply(String emailOff, Map<String, Boolean> changes) {
        Set<String> off = parse(emailOff);
        if (changes != null) {
            for (Map.Entry<String, Boolean> e : changes.entrySet()) {
                if (!CATEGORIES.contains(e.getKey())) {
                    throw new BadRequestException("Unknown email category: " + e.getKey());
                }
                if (e.getValue() == null) {
                    continue;
                }
                if (e.getValue()) {
                    off.remove(e.getKey());
                } else {
                    off.add(e.getKey());
                }
            }
        }
        String value = CATEGORIES.stream().filter(off::contains).collect(Collectors.joining(","));
        return value.isEmpty() ? null : value;
    }

    private static Set<String> parse(String emailOff) {
        if (emailOff == null || emailOff.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(emailOff.split(",")).map(String::trim).filter(CATEGORIES::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}

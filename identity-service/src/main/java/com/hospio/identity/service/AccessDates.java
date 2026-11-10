package com.hospio.identity.service;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Temporary access in Lagos days, and the words people read in notices. A role given "until 1 Dec" works through
 * 1 Dec and ends at midnight after it, Lagos time; that midnight is what {@code expires_at} holds.
 */
public final class AccessDates {

    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private AccessDates() {
    }

    /** The instant a role given until {@code lastDay} stops working: the start of the next Lagos day. */
    public static Instant endOf(LocalDate lastDay) {
        return lastDay.plusDays(1).atStartOfDay(LAGOS).toInstant();
    }

    /** The last Lagos day a role ending at {@code expiresAt} works. */
    public static LocalDate lastDay(Instant expiresAt) {
        return expiresAt.minusMillis(1).atZone(LAGOS).toLocalDate();
    }

    public static String day(LocalDate date) {
        return DAY.format(date);
    }

    public static String roleLabel(Role role) {
        return switch (role) {
            case OWNER -> "Owner";
            case MANAGER -> "Manager";
            case FRONT_OFFICE -> "Front office";
            case HOUSEKEEPING -> "Housekeeping";
            case LAUNDRY -> "Laundry";
            case MAINTENANCE -> "Maintenance";
            case KITCHEN -> "Kitchen";
            case BAR -> "Bar";
            case INVENTORY -> "Inventory";
            case FINANCE -> "Finance";
            default -> role.name();
        };
    }

    public static String levelLabel(AccessLevel level) {
        return switch (level) {
            case FULL -> "Full";
            case EDIT -> "Can edit";
            case VIEW -> "View only";
        };
    }
}

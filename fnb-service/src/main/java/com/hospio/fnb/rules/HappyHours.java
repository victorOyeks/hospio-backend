package com.hospio.fnb.rules;

import com.hospio.fnb.entity.HappyHour;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/** Which happy hour, if any, is running right now at an outlet. A window that ends before it starts runs overnight. */
public final class HappyHours {

    private HappyHours() {
    }

    /** The biggest discount percentage among the active happy hours running at {@code now}; zero when none is. */
    public static BigDecimal activePct(List<HappyHour> happyHours, ZonedDateTime now) {
        BigDecimal best = BigDecimal.ZERO;
        for (HappyHour h : happyHours) {
            if (h.isActive() && runs(h, now) && h.getDiscountPct() != null && h.getDiscountPct().compareTo(best) > 0) {
                best = h.getDiscountPct();
            }
        }
        return best;
    }

    static boolean runs(HappyHour h, ZonedDateTime now) {
        LocalTime t = now.toLocalTime();
        LocalTime from = h.getFromTime();
        LocalTime to = h.getToTime();
        if (from.isBefore(to)) {
            return onDay(h.getDays(), now.getDayOfWeek()) && !t.isBefore(from) && t.isBefore(to);
        }
        // Overnight: the evening part belongs to the day it starts, the early-morning part to the day before.
        if (!t.isBefore(from)) {
            return onDay(h.getDays(), now.getDayOfWeek());
        }
        return t.isBefore(to) && onDay(h.getDays(), now.getDayOfWeek().minus(1));
    }

    /** {@code days} is comma-separated names (MON,TUE or Monday); DAILY or ALL means every day. */
    static boolean onDay(String days, DayOfWeek day) {
        if (days == null || days.isBlank()) {
            return true;
        }
        String want = day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT);
        for (String d : days.split(",")) {
            String x = d.trim().toUpperCase(Locale.ROOT);
            if (x.equals("DAILY") || x.equals("ALL") || x.equals("EVERYDAY") || (x.length() >= 3 && x.substring(0, 3).equals(want))) {
                return true;
            }
        }
        return false;
    }
}

package com.hospio.common.util;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A supplier's payment terms read as a number of days: "14 days", "Net 30" and "30" are 14, 30 and 30; "Pay on delivery", "Cash",
 * blank or anything without a number is 0 (due the day the goods arrive). Weeks and months count 7 and 30 days ("2 weeks" = 14).
 */
public final class PaymentTerms {

    private static final Pattern NUMBER = Pattern.compile("(\\d{1,3})\\s*(day|days|d|week|weeks|wk|wks|month|months)?", Pattern.CASE_INSENSITIVE);

    private PaymentTerms() {
    }

    public static int days(String terms) {
        if (terms == null || terms.isBlank()) {
            return 0;
        }
        Matcher m = NUMBER.matcher(terms);
        if (!m.find()) {
            return 0;
        }
        int n = Integer.parseInt(m.group(1));
        String unit = m.group(2) == null ? "" : m.group(2).toLowerCase(java.util.Locale.ROOT);
        if (unit.startsWith("w")) {
            n *= 7;
        } else if (unit.startsWith("m")) {
            n *= 30;
        }
        return Math.min(n, 365);
    }

    /** When a bill for goods received on {@code received} is due under these terms: the same day when there are none. */
    public static LocalDate due(LocalDate received, Integer days) {
        return days == null || days <= 0 ? received : received.plusDays(days);
    }
}

package com.hospio.tenant.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.tenant.entity.Business;
import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * The checks the back-office onboarding applies to what an admin types (docs/onboarding.md, "Back-office field rules").
 * They are the same rules as identity-service's Phones and the web's shared validate.ts, so the screen and the API refuse
 * the same things. Each {@code clean*} trims, returns null for a blank optional value and throws a 400 with a sentence
 * the screen can show under the field.
 */
public final class Fields {

    /** Percentage fee models: 0 to 100 inclusive (0 is a free period, as platform's fee rules allow). */
    public static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(100);
    /** Per-room-night fee: whole naira, 0 to 1,000,000. */
    public static final BigDecimal MAX_PER_NIGHT = BigDecimal.valueOf(1_000_000);

    private Fields() {
    }

    private static int letters(String v) {
        return v.replaceAll("[^\\p{L}]", "").length();
    }

    /** A person's, business's or hotel's name: 2 to {@code max} characters with at least two letters (so not "0000" or "@#$%"). */
    public static boolean meaningfulName(String v) {
        if (!StringUtils.hasText(v)) {
            return false;
        }
        String t = v.trim();
        return t.length() >= 2 && letters(t) >= 2;
    }

    public static String name(String v, String what, boolean required) {
        if (!StringUtils.hasText(v)) {
            if (required) {
                throw new BadRequestException("Enter " + what);
            }
            return null;
        }
        String t = v.trim().replaceAll("\\s+", " ");
        if (t.length() > 120) {
            throw new BadRequestException("Keep " + what + " under 120 characters");
        }
        if (!meaningfulName(t)) {
            throw new BadRequestException(capital(what) + " must contain letters, not only numbers or symbols");
        }
        return t;
    }

    /** A town or city: letters only (spaces, hyphens and apostrophes allowed), 2 to 80 characters. */
    public static String city(String v, boolean required) {
        if (!StringUtils.hasText(v)) {
            if (required) {
                throw new BadRequestException("Enter the city");
            }
            return null;
        }
        String t = v.trim().replaceAll("\\s+", " ");
        if (t.length() > 80) {
            throw new BadRequestException("Keep the city under 80 characters");
        }
        if (letters(t) < 2 || t.matches(".*\\d.*")) {
            throw new BadRequestException("Enter a real city name");
        }
        return t;
    }

    /** A street address: 5 to 200 characters with at least three letters. */
    public static String address(String v, boolean required) {
        if (!StringUtils.hasText(v)) {
            if (required) {
                throw new BadRequestException("Enter the address");
            }
            return null;
        }
        String t = v.trim().replaceAll("\\s+", " ");
        if (t.length() > 200) {
            throw new BadRequestException("Keep the address under 200 characters");
        }
        if (t.length() < 5 || letters(t) < 3) {
            throw new BadRequestException("Enter a real street address");
        }
        return t;
    }

    public static String email(String v, String what, boolean required) {
        if (!StringUtils.hasText(v)) {
            if (required) {
                throw new BadRequestException("Enter " + what);
            }
            return null;
        }
        String t = v.trim().toLowerCase(Locale.ROOT);
        if (t.length() > 160 || !t.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")) {
            throw new BadRequestException(capital(what) + " must be a valid email, like name@hotel.com");
        }
        return t;
    }

    public static String digits(String phone) {
        return phone == null ? "" : phone.replaceAll("[^0-9]", "");
    }

    /** The last ten digits: what "+234 803 555 0192" and "0803 555 0192" have in common. */
    public static String phoneKey(String phone) {
        String d = digits(phone);
        return d.length() <= 10 ? d : d.substring(d.length() - 10);
    }

    /** 10 to 15 digits with an optional leading +, spaces, brackets or dashes; the last ten not all one digit. */
    public static boolean validPhone(String phone) {
        if (!StringUtils.hasText(phone) || !phone.trim().matches("^\\+?[0-9 ()-]{7,24}$")) {
            return false;
        }
        String d = digits(phone);
        return d.length() >= 10 && d.length() <= 15 && !phoneKey(d).matches("^(\\d)\\1*$");
    }

    public static String phone(String v, String what, boolean required) {
        if (!StringUtils.hasText(v)) {
            if (required) {
                throw new BadRequestException("Enter " + what);
            }
            return null;
        }
        if (!validPhone(v)) {
            throw new BadRequestException(capital(what) + " must be a real phone number, like +234 803 555 0192");
        }
        return v.trim().replaceAll("\\s+", " ");
    }

    /**
     * A CAC registration number: RC (companies), BN (business names) or IT (incorporated trustees) and 1 to 8 digits,
     * written "RC 1873402", "RC1873402" or just "1873402" (a company). Stored as typed, tidied to "RC 1873402".
     */
    public static String registrationNumber(String v) {
        if (!StringUtils.hasText(v)) {
            return null;
        }
        String key = registrationKey(v);
        if (key == null) {
            throw new BadRequestException("Enter the CAC number as on the certificate, like RC 1873402 or BN 3456789");
        }
        return key.substring(0, 2) + " " + key.substring(2);
    }

    /** "rc-1873402" and "1873402" → "RC1873402"; null when it is not a CAC number (no digits, all zeros, wrong prefix). */
    public static String registrationKey(String v) {
        if (!StringUtils.hasText(v)) {
            return null;
        }
        String t = v.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s./-]", "");
        if (t.matches("^\\d{1,8}$")) {
            t = "RC" + t;
        }
        if (!t.matches("^(RC|BN|IT)\\d{1,8}$") || t.substring(2).matches("^0+$")) {
            return null;
        }
        return t;
    }

    /** A business name compared with others: trimmed, single-spaced, lower case. */
    public static String nameKey(String v) {
        return v == null ? "" : v.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** The fee value for a model: required, 0 to 100 for a percentage (two decimals at most), 0 to ₦1,000,000 per room night. */
    public static BigDecimal fee(Business.FeeModel model, BigDecimal value) {
        if (model == null) {
            throw new BadRequestException("Choose the fee model");
        }
        if (value == null) {
            throw new BadRequestException(model == Business.FeeModel.PER_ROOM_NIGHT ? "Enter the fee per room night" : "Enter the share (%)");
        }
        if (model == Business.FeeModel.PER_ROOM_NIGHT) {
            if (value.signum() < 0 || value.compareTo(MAX_PER_NIGHT) > 0) {
                throw new BadRequestException("The fee per room night must be between ₦0 and ₦1,000,000");
            }
            return value;
        }
        if (value.signum() < 0 || value.compareTo(MAX_PERCENT) > 0) {
            throw new BadRequestException("The share must be between 0 and 100 (%)");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException("Use at most two decimal places for the share, like 1.5 or 2.25");
        }
        return value;
    }

    /** "14:00": hours 00-23 and minutes 00-59. */
    public static String time(String v, String what, String fallback) {
        if (v == null) {
            return fallback;
        }
        String t = v.trim();
        if (!t.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
            throw new BadRequestException("Enter the " + what + " time, like 14:00");
        }
        return t;
    }

    private static String capital(String what) {
        String w = what.replaceFirst("^(the|a|an) ", "");
        return Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }
}

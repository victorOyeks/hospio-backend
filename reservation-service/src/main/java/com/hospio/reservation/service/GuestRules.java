package com.hospio.reservation.service;

import com.hospio.common.exception.BadRequestException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.util.StringUtils;

/**
 * What a booking accepts about the guest and the party, the same rules as the web's validate.ts (and identity's Phones):
 * a name with letters in it, a phone of 10 to 15 digits, whole guest counts, and an arrival time on the clock.
 */
public final class GuestRules {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private GuestRules() {
    }

    /** The digits only. */
    public static String digits(String phone) {
        return phone == null ? "" : phone.replaceAll("[^0-9]", "");
    }

    /** The last ten digits: what "+234 803 555 0192" and "0803 555 0192" have in common, so one person is one guest. */
    public static String phoneKey(String phone) {
        String d = digits(phone);
        return d.length() <= 10 ? d : d.substring(d.length() - 10);
    }

    /** A guest's name: 2 to 150 characters with at least two letters (so not "0000" or "@#$%"). */
    public static String name(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BadRequestException("Enter the guest's name");
        }
        String t = name.trim().replaceAll("\\s+", " ");
        if (t.length() > 150) {
            throw new BadRequestException("Keep the guest's name under 150 characters");
        }
        if (t.length() < 2 || t.replaceAll("[^\\p{L}]", "").length() < 2) {
            throw new BadRequestException("The guest's name must contain letters, not only numbers or symbols");
        }
        return t;
    }

    /** A phone of 10 to 15 digits with an optional leading +, spaces, brackets or dashes; not one digit repeated. */
    public static String phone(String phone) {
        if (!StringUtils.hasText(phone)) {
            throw new BadRequestException("Enter the guest's phone number");
        }
        String t = phone.trim();
        String d = digits(t);
        if (!t.matches("^\\+?[0-9 ()-]{7,24}$") || d.length() < 10 || d.length() > 15 || phoneKey(d).matches("^(\\d)\\1*$")) {
            throw new BadRequestException("Enter a real phone number for the guest, like +234 803 555 0192");
        }
        return t.replaceAll("\\s+", " ");
    }

    /** Adults: 1 to 20 (blank is one). */
    public static int adults(Integer adults) {
        int n = adults == null ? 1 : adults;
        if (n < 1 || n > 20) {
            throw new BadRequestException("Adults must be between 1 and 20");
        }
        return n;
    }

    /** Children: 0 to 20 (blank is none). */
    public static int children(Integer children) {
        int n = children == null ? 0 : children;
        if (n < 0 || n > 20) {
            throw new BadRequestException("Children must be between 0 and 20");
        }
        return n;
    }

    /** The expected arrival, "15:30"; blank is not known. */
    public static String arrival(String time) {
        if (!StringUtils.hasText(time)) {
            return null;
        }
        try {
            return LocalTime.parse(time.trim(), HH_MM).format(HH_MM);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Enter the expected arrival as a time, like 15:30");
        }
    }
}

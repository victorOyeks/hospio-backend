package com.hospio.property.service;

import com.hospio.common.exception.BadRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * What a room type name, a room number and a floor may be (docs/onboarding.md, "Rooms"). The web's onboarding checks are
 * stricter (a first number like 101 or A101); these are what any screen must respect.
 */
public final class RoomRules {

    private static final Pattern TRAILING_NUMBER = Pattern.compile("^(.*?)(\\d+)$");

    private RoomRules() {
    }

    /** A room type: 2 to 60 characters with at least two letters ("Standard", "Deluxe King"), single-spaced. */
    public static String typeName(String v) {
        if (!StringUtils.hasText(v)) {
            throw new BadRequestException("Enter the room type, like Standard or Deluxe");
        }
        String t = v.trim().replaceAll("\\s+", " ");
        if (t.length() > 60) {
            throw new BadRequestException("Keep the room type under 60 characters");
        }
        if (t.length() < 2 || t.replaceAll("[^\\p{L}]", "").length() < 2) {
            throw new BadRequestException("The room type must contain letters, like Standard or Deluxe");
        }
        return t;
    }

    /** Guests a room type sleeps, when given: 1 to 20. */
    public static void guests(Integer adults) {
        if (adults != null && (adults < 1 || adults > 20)) {
            throw new BadRequestException("Guests per room must be between 1 and 20");
        }
    }

    /** A room number: up to 10 letters, digits or dashes with at least one digit that is not zero ("101", "A101", "G-02"). */
    public static String number(String v) {
        if (!StringUtils.hasText(v)) {
            throw new BadRequestException("Enter the room number, like 101");
        }
        String t = v.trim().toUpperCase(Locale.ROOT);
        if (!t.matches("^[A-Z0-9-]{1,10}$") || !t.matches(".*[1-9].*")) {
            throw new BadRequestException("Room number " + v.trim() + " is not valid. Use digits with up to three letters, like 101 or A101");
        }
        return t;
    }

    /** A floor, when given: up to 20 characters ("1", "G", "Floor 2"). */
    public static String floor(String v) {
        if (!StringUtils.hasText(v)) {
            return null;
        }
        String t = v.trim();
        if (t.length() > 20) {
            throw new BadRequestException("Keep the floor under 20 characters");
        }
        return t;
    }

    /** A name compared with others: trimmed, single-spaced, lower case. */
    public static String key(String v) {
        return v == null ? "" : v.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** ["101","102","103","201"] → "101–103, 201" (at most six groups, then "…"). */
    public static String ranges(List<String> numbers) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < numbers.size()) {
            int j = i;
            while (j + 1 < numbers.size() && follows(numbers.get(j), numbers.get(j + 1))) {
                j++;
            }
            out.add(j > i ? numbers.get(i) + "–" + numbers.get(j) : numbers.get(i));
            i = j + 1;
        }
        return out.size() > 6 ? String.join(", ", out.subList(0, 6)) + " …" : String.join(", ", out);
    }

    private static boolean follows(String a, String b) {
        Matcher ma = TRAILING_NUMBER.matcher(a);
        Matcher mb = TRAILING_NUMBER.matcher(b);
        return ma.matches() && mb.matches() && ma.group(1).equals(mb.group(1))
                && Long.parseLong(mb.group(2)) == Long.parseLong(ma.group(2)) + 1;
    }
}

package com.hospio.identity.service;

import com.hospio.common.exception.BadRequestException;
import org.springframework.util.StringUtils;

/** Phone numbers as people type them: "+234 803 555 0192", "08035550192", "234 803 555 0192". */
public final class Phones {

    private Phones() {
    }

    /** The digits only. */
    public static String digits(String phone) {
        return phone == null ? "" : phone.replaceAll("[^0-9]", "");
    }

    /** The last ten digits: what two ways of writing one Nigerian number have in common. */
    public static String key(String phone) {
        String d = digits(phone);
        return d.length() <= 10 ? d : d.substring(d.length() - 10);
    }

    /** True for 10 to 15 digits (with an optional leading +), not all one repeated digit. */
    public static boolean valid(String phone) {
        if (!StringUtils.hasText(phone) || !phone.trim().matches("^\\+?[0-9 ()-]{7,24}$")) {
            return false;
        }
        String d = digits(phone);
        // Not one digit repeated, and the subscriber part (last ten digits) not all one digit: "+234 000 000 0000".
        return d.length() >= 10 && d.length() <= 15 && !key(d).matches("^(\\d)\\1*$");
    }

    /** Trims and checks; blank stays null. */
    public static String clean(String phone, String field) {
        if (!StringUtils.hasText(phone)) {
            return null;
        }
        if (!valid(phone)) {
            throw new BadRequestException(field + " must be a real phone number, like +234 803 555 0192");
        }
        return phone.trim().replaceAll("\\s+", " ");
    }

    /** A person's or business's name: two or more characters with at least two letters. */
    public static boolean meaningfulName(String name) {
        if (!StringUtils.hasText(name)) {
            return false;
        }
        String t = name.trim();
        return t.length() >= 2 && t.replaceAll("[^\\p{L}]", "").length() >= 2;
    }
}

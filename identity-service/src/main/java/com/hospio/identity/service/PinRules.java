package com.hospio.identity.service;

import com.hospio.common.exception.BadRequestException;

/**
 * What a PIN may be (docs/shared-devices.md): 6 digits, not one digit repeated (1111), not a straight run up or down
 * (1234, 4321, 56789) and not the last digits of the person's own phone. Each refusal says why in words a cashier reads.
 */
public final class PinRules {

    private PinRules() {
    }

    /** Throws 400 with the reason when the PIN is not allowed. */
    public static void check(String pin, String phone) {
        String why = problem(pin, phone);
        if (why != null) {
            throw new BadRequestException(why);
        }
    }

    /** Why the PIN is refused, or null when it is fine. */
    static String problem(String pin, String phone) {
        if (pin == null || !pin.matches("\\d{6}")) {
            return "A PIN is 6 digits";
        }
        if (pin.chars().distinct().count() == 1) {
            return "Don't use one digit repeated, like " + pin + ". Pick a PIN that's harder to guess";
        }
        if (straight(pin, 1) || straight(pin, -1)) {
            return "Don't use digits in a row, like " + pin + ". Pick a PIN that's harder to guess";
        }
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.length() >= pin.length() && digits.endsWith(pin)) {
            return "Don't use the end of your phone number. Pick a PIN that's harder to guess";
        }
        return null;
    }

    private static boolean straight(String pin, int step) {
        for (int i = 1; i < pin.length(); i++) {
            if (pin.charAt(i) - pin.charAt(i - 1) != step) {
                return false;
            }
        }
        return true;
    }
}

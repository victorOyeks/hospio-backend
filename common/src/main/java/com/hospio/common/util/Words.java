package com.hospio.common.util;

/**
 * Free text that must say something (a pay-out reason, a till note): at least {@link #MIN_LETTERS} letters, so "1234567-0-=-"
 * or "x" is refused while "Diesel 20L" passes. Digits and signs are fine alongside the letters.
 */
public final class Words {

    public static final int MIN_LETTERS = 3;

    private Words() {
    }

    public static boolean meaningful(String text) {
        if (text == null) {
            return false;
        }
        return text.codePoints().filter(Character::isLetter).count() >= MIN_LETTERS;
    }
}

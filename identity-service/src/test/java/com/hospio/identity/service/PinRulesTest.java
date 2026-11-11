package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** PINs are exactly 6 digits, never one digit repeated, a straight run or the end of the person's phone. */
class PinRulesTest {

    @Test
    void trivialPinsAreRefused() {
        for (String pin : new String[]{"111111", "000000", "123456", "654321", "012345", "987654"}) {
            assertThat(PinRules.problem(pin, null)).as(pin).isNotNull();
        }
    }

    @Test
    void exactlySixDigits() {
        for (String pin : new String[]{"2580", "13579", "1234567", "12a456", "", null}) {
            assertThat(PinRules.problem(pin, null)).as(String.valueOf(pin)).isEqualTo("A PIN is 6 digits");
        }
    }

    @Test
    void theEndOfTheirPhoneIsRefused() {
        assertThat(PinRules.problem("550192", "+234 803 555 0192")).contains("phone");
        assertThat(PinRules.problem("550192", "08035550192")).contains("phone");
        assertThat(PinRules.problem("550193", "+234 803 555 0192")).isNull();
    }

    @Test
    void ordinaryPinsPass() {
        for (String pin : new String[]{"258013", "135792", "482691", "908172", "112358"}) {
            assertThat(PinRules.problem(pin, "+2348035550192")).as(pin).isNull();
        }
    }
}

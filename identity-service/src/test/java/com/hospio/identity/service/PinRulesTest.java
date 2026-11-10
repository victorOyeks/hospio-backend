package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** PINs are 4-6 digits, never one digit repeated, a straight run or the end of the person's phone. */
class PinRulesTest {

    @Test
    void trivialPinsAreRefused() {
        for (String pin : new String[]{"1111", "000000", "1234", "4321", "0123", "98765", "123456", "654321"}) {
            assertThat(PinRules.problem(pin, null)).as(pin).isNotNull();
        }
    }

    @Test
    void lengthAndDigits() {
        assertThat(PinRules.problem("123", null)).isEqualTo("A PIN is 4 to 6 digits");
        assertThat(PinRules.problem("1234567", null)).isEqualTo("A PIN is 4 to 6 digits");
        assertThat(PinRules.problem("12a4", null)).isEqualTo("A PIN is 4 to 6 digits");
    }

    @Test
    void theEndOfTheirPhoneIsRefused() {
        assertThat(PinRules.problem("0192", "+234 803 555 0192")).contains("phone");
        assertThat(PinRules.problem("550192", "08035550192")).contains("phone");
        assertThat(PinRules.problem("0193", "+234 803 555 0192")).isNull();
    }

    @Test
    void ordinaryPinsPass() {
        for (String pin : new String[]{"2580", "1357", "4826", "13579", "908172", "1123"}) {
            assertThat(PinRules.problem(pin, "+2348035550192")).as(pin).isNull();
        }
    }
}

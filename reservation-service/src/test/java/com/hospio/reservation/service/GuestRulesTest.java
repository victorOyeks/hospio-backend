package com.hospio.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

/** QA sheet 3 (DEF-054, 057–061): what New booking accepts about the guest and the party. */
class GuestRulesTest {

    @Test
    void namesNeedLetters() {
        assertThat(GuestRules.name("  Adaeze   Okoro ")).isEqualTo("Adaeze Okoro");
        assertThat(GuestRules.name("Ọlá")).isEqualTo("Ọlá");
        for (String bad : new String[] {"0000", "@#$%", "...", "A", " "}) {
            assertThatThrownBy(() -> GuestRules.name(bad)).as(bad).isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void phonesAreRealNumbers() {
        assertThat(GuestRules.phone("+234 803 111 0001")).isEqualTo("+234 803 111 0001");
        assertThat(GuestRules.phone("08031110001")).isEqualTo("08031110001");
        for (String bad : new String[] {"09023Sam**", "0000", "abc123", "12345", "+234 000 000 0000", "Blessing"}) {
            assertThatThrownBy(() -> GuestRules.phone(bad)).as(bad).isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void oneNumberWrittenThreeWaysIsOneKey() {
        assertThat(GuestRules.phoneKey("+234 803 111 0001"))
                .isEqualTo(GuestRules.phoneKey("08031110001"))
                .isEqualTo(GuestRules.phoneKey("2348031110001"))
                .isEqualTo("8031110001");
    }

    @Test
    void guestCountsAreWholeNumbersInRange() {
        assertThat(GuestRules.adults(null)).isEqualTo(1);
        assertThat(GuestRules.children(null)).isZero();
        assertThat(GuestRules.adults(2)).isEqualTo(2);
        assertThatThrownBy(() -> GuestRules.adults(0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> GuestRules.adults(-44440)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> GuestRules.adults(99999)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> GuestRules.children(-1111)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void arrivalIsAClockTime() {
        assertThat(GuestRules.arrival("15:30")).isEqualTo("15:30");
        assertThat(GuestRules.arrival(" ")).isNull();
        for (String bad : new String[] {"-7564", "-3pm", "-1111", "25:99", "abc", "3pm"}) {
            assertThatThrownBy(() -> GuestRules.arrival(bad)).as(bad).isInstanceOf(BadRequestException.class);
        }
    }
}

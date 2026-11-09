package com.hospio.fnb.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.fnb.entity.HappyHour;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class HappyHoursTest {

    // 2026-11-09 is a Monday, 2026-11-14 a Saturday.
    private static ZonedDateTime at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(Days.LAGOS);
    }

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 11, 14);

    private static HappyHour weekdayEvening(String pct) {
        return HappyHour.builder().name("Happy hour").fromTime(LocalTime.of(17, 0)).toTime(LocalTime.of(19, 0)).days("MON,TUE,WED,THU,FRI")
                .discountPct(new BigDecimal(pct)).build();
    }

    @Test
    void runsOnItsDaysInsideItsWindowOnly() {
        List<HappyHour> hh = List.of(weekdayEvening("20"));

        assertThat(HappyHours.activePct(hh, at(MONDAY, 17, 0))).isEqualByComparingTo("20");
        assertThat(HappyHours.activePct(hh, at(MONDAY, 18, 59))).isEqualByComparingTo("20");
        assertThat(HappyHours.activePct(hh, at(MONDAY, 19, 0))).isEqualByComparingTo("0");
        assertThat(HappyHours.activePct(hh, at(MONDAY, 16, 59))).isEqualByComparingTo("0");
        assertThat(HappyHours.activePct(hh, at(SATURDAY, 18, 0))).isEqualByComparingTo("0");
    }

    @Test
    void anInactiveHappyHourAndTheBiggestOfTwoOverlappingOnes() {
        HappyHour off = weekdayEvening("50");
        off.setActive(false);

        assertThat(HappyHours.activePct(List.of(off), at(MONDAY, 18, 0))).isEqualByComparingTo("0");
        assertThat(HappyHours.activePct(List.of(off, weekdayEvening("10"), weekdayEvening("25")), at(MONDAY, 18, 0))).isEqualByComparingTo("25");
    }

    @Test
    void anOvernightWindowBelongsToTheDayItStarts() {
        HappyHour late = HappyHour.builder().name("Late").fromTime(LocalTime.of(22, 0)).toTime(LocalTime.of(2, 0)).days("FRI")
                .discountPct(new BigDecimal("15")).build();
        LocalDate friday = LocalDate.of(2026, 11, 13);

        assertThat(HappyHours.activePct(List.of(late), at(friday, 23, 0))).isEqualByComparingTo("15");
        assertThat(HappyHours.activePct(List.of(late), at(SATURDAY, 1, 0))).isEqualByComparingTo("15");
        assertThat(HappyHours.activePct(List.of(late), at(friday, 1, 0))).isEqualByComparingTo("0");
    }
}

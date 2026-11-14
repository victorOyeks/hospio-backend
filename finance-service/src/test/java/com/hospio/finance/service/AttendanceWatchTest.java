package com.hospio.finance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** docs/workforce.md: when nobody-in and forgotten clock-out notices go out. */
class AttendanceWatchTest {

    private static final Instant START = Instant.parse("2026-11-14T05:00:00Z"); // 06:00 Lagos
    private static final Instant END = START.plus(Duration.ofHours(8));

    @Test
    void notInOnlyAfterTheLateAllowanceAndWhileTheShiftRuns() {
        assertThat(AttendanceWatch.notInDue(START, END, 15, START.plus(Duration.ofMinutes(10)))).isFalse();
        assertThat(AttendanceWatch.notInDue(START, END, 15, START.plus(Duration.ofMinutes(16)))).isTrue();
        assertThat(AttendanceWatch.notInDue(START, END, 15, END.plus(Duration.ofMinutes(1)))).isFalse();
    }

    @Test
    void anOpenClockInIsRemindedAnHourAfterTheShiftThenClosedAfterADay() {
        Instant in = START.minus(Duration.ofMinutes(5));
        assertThat(AttendanceWatch.decide(in, END, null, END.plus(Duration.ofMinutes(30)))).isEqualTo(AttendanceWatch.OpenAction.NONE);
        assertThat(AttendanceWatch.decide(in, END, null, END.plus(Duration.ofMinutes(61)))).isEqualTo(AttendanceWatch.OpenAction.REMIND);
        assertThat(AttendanceWatch.decide(in, END, END, END.plus(Duration.ofHours(2)))).isEqualTo(AttendanceWatch.OpenAction.NONE);
        assertThat(AttendanceWatch.decide(in, END, END, in.plus(Duration.ofHours(25)))).isEqualTo(AttendanceWatch.OpenAction.CLOSE);
    }

    @Test
    void withoutAShiftTheReminderWaitsFourteenHours() {
        Instant in = START;
        assertThat(AttendanceWatch.decide(in, null, null, in.plus(Duration.ofHours(13)))).isEqualTo(AttendanceWatch.OpenAction.NONE);
        assertThat(AttendanceWatch.decide(in, null, null, in.plus(Duration.ofHours(15)))).isEqualTo(AttendanceWatch.OpenAction.REMIND);
    }

    @Test
    void aForgottenClockInClosesAtTheShiftEndOrTwelveHoursLater() {
        assertThat(AttendanceWatch.closeAt(START, END)).isEqualTo(END);
        assertThat(AttendanceWatch.closeAt(START, null)).isEqualTo(START.plus(Duration.ofHours(12)));
        assertThat(AttendanceWatch.closeAt(END.plus(Duration.ofHours(1)), END)).isEqualTo(END.plus(Duration.ofHours(13)));
    }

    @Test
    void clockTimesReadInHotelTime() {
        assertThat(WorkforceService.hm(START)).isEqualTo("06:00");
    }
}

package com.hospio.finance.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.finance.entity.ClockEntry;
import com.hospio.finance.entity.Shift;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Rota attendance: on time is PRESENT, past the late allowance is LATE, a finished shift with no clock-in is ABSENT. */
class AttendanceTest {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 9);

    private final Shift morning = Shift.builder().userId("u1").role("FRONT_OFFICE").shiftDate(DAY)
            .startsAt(LocalTime.of(7, 0)).endsAt(LocalTime.of(15, 0)).build();

    private static Instant at(int hour, int minute) {
        return LocalDateTime.of(DAY, LocalTime.of(hour, minute)).atZone(LAGOS).toInstant();
    }

    private static ClockEntry clockedInAt(int hour, int minute) {
        return ClockEntry.builder().userId("u1").clockIn(at(hour, minute)).build();
    }

    @Test
    void clockingInWithinTheAllowanceIsPresent() {
        assertThat(Attendance.of(morning, List.of(clockedInAt(6, 55)), at(9, 0), 15, LAGOS)).isEqualTo(Attendance.Status.PRESENT);
        assertThat(Attendance.of(morning, List.of(clockedInAt(7, 15)), at(9, 0), 15, LAGOS)).isEqualTo(Attendance.Status.PRESENT);
    }

    @Test
    void clockingInAfterTheAllowanceIsLate() {
        assertThat(Attendance.of(morning, List.of(clockedInAt(7, 16)), at(9, 0), 15, LAGOS)).isEqualTo(Attendance.Status.LATE);
        assertThat(Attendance.of(morning, List.of(clockedInAt(7, 16)), at(9, 0), 30, LAGOS)).isEqualTo(Attendance.Status.PRESENT);
    }

    @Test
    void noClockInOnceTheShiftHasEndedIsAbsent() {
        assertThat(Attendance.of(morning, List.of(), at(15, 1), 15, LAGOS)).isEqualTo(Attendance.Status.ABSENT);
    }

    @Test
    void noClockInYetWhileTheShiftIsStillToComeOrRunningIsUnknown() {
        assertThat(Attendance.of(morning, List.of(), at(6, 0), 15, LAGOS)).isNull();
        assertThat(Attendance.of(morning, List.of(), at(10, 0), 15, LAGOS)).isNull();
    }

    @Test
    void aClockInFromAnotherDayDoesNotCount() {
        ClockEntry yesterday = ClockEntry.builder().userId("u1").clockIn(at(7, 0).minusSeconds(24 * 3600)).build();
        assertThat(Attendance.of(morning, List.of(yesterday), at(16, 0), 15, LAGOS)).isEqualTo(Attendance.Status.ABSENT);
    }

    @Test
    void aCancelledShiftHasNoAttendance() {
        morning.setStatus(Shift.Status.CANCELLED);
        assertThat(Attendance.of(morning, List.of(), at(16, 0), 15, LAGOS)).isNull();
    }

    @Test
    void minutesWorkedCountsClosedAndOpenEntriesClippedToTheDay() {
        Instant dayStart = at(0, 0);
        ClockEntry closed = ClockEntry.builder().userId("u1").clockIn(at(7, 0)).clockOut(at(9, 30)).build();
        ClockEntry open = clockedInAt(13, 0);
        ClockEntry overnight = ClockEntry.builder().userId("u1").clockIn(dayStart.minusSeconds(3600)).clockOut(at(1, 0)).build();

        assertThat(Attendance.minutesWorked(List.of(closed, open), dayStart, at(14, 0))).isEqualTo(150 + 60);
        assertThat(Attendance.minutesWorked(List.of(overnight), dayStart, at(14, 0))).isEqualTo(60);
    }
}

package com.hospio.finance.service;

import com.hospio.finance.entity.ClockEntry;
import com.hospio.finance.entity.Shift;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** Rota attendance from the clock: PRESENT, LATE, ABSENT, or null while the shift hasn't ended and nobody has clocked in. */
public final class Attendance {

    /** A clock-in this long before the shift starts still counts as arriving for it. */
    static final Duration EARLY = Duration.ofHours(3);

    private Attendance() {
    }

    public enum Status { PRESENT, LATE, ABSENT }

    public static Status of(Shift shift, List<ClockEntry> entries, Instant now, int lateMinutes, ZoneId zone) {
        if (shift.getStatus() == Shift.Status.CANCELLED) {
            return null;
        }
        Instant start = LocalDateTime.of(shift.getShiftDate(), shift.getStartsAt()).atZone(zone).toInstant();
        // A shift ending at or before its start time ends the next morning (night shift).
        Instant end = LocalDateTime.of(shift.getEndsAt().isAfter(shift.getStartsAt()) ? shift.getShiftDate() : shift.getShiftDate().plusDays(1), shift.getEndsAt())
                .atZone(zone).toInstant();
        Instant first = entries.stream().map(ClockEntry::getClockIn)
                .filter(t -> !t.isBefore(start.minus(EARLY)) && !t.isAfter(end)).min(Instant::compareTo).orElse(null);
        if (first == null) {
            return now.isAfter(end) ? Status.ABSENT : null;
        }
        return first.isAfter(start.plus(Duration.ofMinutes(lateMinutes))) ? Status.LATE : Status.PRESENT;
    }

    /** Minutes worked since {@code dayStart}: closed entries and the open one up to {@code now}, clipped to the day's start. */
    public static long minutesWorked(List<ClockEntry> entries, Instant dayStart, Instant now) {
        long minutes = 0;
        for (ClockEntry e : entries) {
            Instant from = e.getClockIn().isBefore(dayStart) ? dayStart : e.getClockIn();
            Instant to = e.getClockOut() == null ? now : e.getClockOut();
            if (to.isAfter(from)) {
                minutes += Duration.between(from, to).toMinutes();
            }
        }
        return minutes;
    }
}

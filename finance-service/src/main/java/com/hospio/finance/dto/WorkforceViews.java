package com.hospio.finance.dto;

import com.hospio.finance.entity.Shift;
import com.hospio.finance.service.Attendance;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public final class WorkforceViews {

    private WorkforceViews() {
    }

    /**
     * A rota row: the shift plus whether the person is clocked in right now and how the shift went. {@code attendance} is
     * PRESENT, LATE or ABSENT, and null while the shift is still to come or running with no clock-in yet.
     */
    public record ShiftView(String id, String hotelId, String userId, String userName, String role, LocalDate shiftDate, LocalTime startsAt, LocalTime endsAt,
                            Shift.Status status, boolean clockedIn, Attendance.Status attendance, Instant createdAt, Instant updatedAt) {

        public static ShiftView of(Shift s, boolean clockedIn, Attendance.Status attendance) {
            return new ShiftView(s.getId(), s.getHotelId(), s.getUserId(), s.getUserName(), s.getRole(), s.getShiftDate(), s.getStartsAt(), s.getEndsAt(),
                    s.getStatus(), clockedIn, attendance, s.getCreatedAt(), s.getUpdatedAt());
        }
    }

    public record ClockStatus(boolean clockedIn, Instant since, long todayMinutes) {
    }

    public record ClockView(String id, String userId, String userName, Instant clockIn, Instant clockOut, boolean onNow, String note) {
    }
}

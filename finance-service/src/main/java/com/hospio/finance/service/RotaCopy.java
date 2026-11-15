package com.hospio.finance.service;

import com.hospio.finance.entity.LeaveRequest;
import com.hospio.finance.entity.Shift;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Copy last week" on the rota (docs/workforce.md): every shift of the source week moves forward to the same weekday of the
 * target week, except
 * <ul>
 *   <li>days already gone (a past shift would only show as absent);</li>
 *   <li>a person who already has a shift that day (copying twice adds nothing);</li>
 *   <li>a person on approved leave that day;</li>
 *   <li>people the caller leaves out (suspended or paused staff).</li>
 * </ul>
 */
final class RotaCopy {

    record Plan(List<Shift> add, int alreadyOnRota, int onLeave, int past, int left) {
    }

    private RotaCopy() {
    }

    static Plan plan(Collection<Shift> source, Collection<Shift> existing, Collection<LeaveRequest> approvedLeave, Set<String> skipUserIds,
                     long shiftDays, LocalDate today) {
        Set<String> taken = new HashSet<>();
        for (Shift e : existing) {
            if (e.getStatus() != Shift.Status.CANCELLED) {
                taken.add(e.getUserId() + "|" + e.getShiftDate());
            }
        }
        List<Shift> add = new ArrayList<>();
        int already = 0;
        int leave = 0;
        int past = 0;
        int left = 0;
        for (Shift s : source) {
            if (s.getStatus() == Shift.Status.CANCELLED) {
                continue;
            }
            LocalDate date = s.getShiftDate().plusDays(shiftDays);
            if (skipUserIds.contains(s.getUserId())) {
                left++;
            } else if (date.isBefore(today)) {
                past++;
            } else if (onLeave(s.getUserId(), date, approvedLeave)) {
                leave++;
            } else if (!taken.add(s.getUserId() + "|" + date)) {
                already++;
            } else {
                add.add(Shift.builder().userId(s.getUserId()).userName(s.getUserName()).role(s.getRole()).shiftDate(date)
                        .startsAt(s.getStartsAt()).endsAt(s.getEndsAt()).build());
            }
        }
        return new Plan(add, already, leave, past, left);
    }

    static long days(LocalDate fromWeek, LocalDate toWeek) {
        return ChronoUnit.DAYS.between(fromWeek, toWeek);
    }

    private static boolean onLeave(String userId, LocalDate date, Collection<LeaveRequest> leave) {
        return leave.stream().anyMatch(l -> l.getUserId().equals(userId) && l.getStatus() == LeaveRequest.Status.APPROVED
                && !date.isBefore(l.getFromDate()) && !date.isAfter(l.getToDate()));
    }
}

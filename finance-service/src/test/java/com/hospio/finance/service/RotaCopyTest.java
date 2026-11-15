package com.hospio.finance.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.finance.entity.LeaveRequest;
import com.hospio.finance.entity.Shift;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** docs/workforce.md: "Copy last week" on the rota. */
class RotaCopyTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 9);
    private static final LocalDate NEXT_MON = MON.plusDays(7);

    private static Shift shift(String user, LocalDate date, Shift.Status status) {
        return Shift.builder().userId(user).userName(user).role("HOUSEKEEPING").shiftDate(date)
                .startsAt(LocalTime.of(6, 0)).endsAt(LocalTime.of(14, 0)).status(status).build();
    }

    @Test
    void eachShiftMovesToTheSameWeekdayWithItsTimes() {
        RotaCopy.Plan p = RotaCopy.plan(List.of(shift("a", MON, Shift.Status.SCHEDULED), shift("b", MON.plusDays(3), Shift.Status.COVERED)),
                List.of(), List.of(), Set.of(), 7, MON);
        assertThat(p.add()).extracting(Shift::getUserId, Shift::getShiftDate, Shift::getStartsAt, Shift::getStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("a", NEXT_MON, LocalTime.of(6, 0), Shift.Status.SCHEDULED),
                        org.assertj.core.groups.Tuple.tuple("b", NEXT_MON.plusDays(3), LocalTime.of(6, 0), Shift.Status.SCHEDULED));
    }

    @Test
    void cancelledShiftsAreNotCopiedAndCopyingTwiceAddsNothing() {
        List<Shift> source = List.of(shift("a", MON, Shift.Status.SCHEDULED), shift("c", MON, Shift.Status.CANCELLED));
        RotaCopy.Plan first = RotaCopy.plan(source, List.of(), List.of(), Set.of(), 7, MON);
        assertThat(first.add()).hasSize(1);
        RotaCopy.Plan again = RotaCopy.plan(source, first.add(), List.of(), Set.of(), 7, MON);
        assertThat(again.add()).isEmpty();
        assertThat(again.alreadyOnRota()).isEqualTo(1);
    }

    @Test
    void leavePastDaysAndSkippedPeopleAreLeftOut() {
        LeaveRequest leave = LeaveRequest.builder().userId("a").fromDate(NEXT_MON).toDate(NEXT_MON.plusDays(1)).status(LeaveRequest.Status.APPROVED).build();
        LeaveRequest pending = LeaveRequest.builder().userId("b").fromDate(NEXT_MON).toDate(NEXT_MON).status(LeaveRequest.Status.PENDING).build();
        RotaCopy.Plan p = RotaCopy.plan(List.of(
                        shift("a", MON, Shift.Status.SCHEDULED),
                        shift("a", MON.plusDays(2), Shift.Status.SCHEDULED),
                        shift("b", MON, Shift.Status.SCHEDULED),
                        shift("s", MON.plusDays(4), Shift.Status.SCHEDULED),
                        shift("p", MON.minusDays(1), Shift.Status.SCHEDULED)),
                List.of(), List.of(leave, pending), Set.of("s"), 7, NEXT_MON);
        assertThat(p.add()).extracting(Shift::getUserId, Shift::getShiftDate)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("a", NEXT_MON.plusDays(2)), org.assertj.core.groups.Tuple.tuple("b", NEXT_MON));
        assertThat(p.onLeave()).isEqualTo(1);
        assertThat(p.left()).isEqualTo(1);
        assertThat(p.past()).isEqualTo(1);
    }
}

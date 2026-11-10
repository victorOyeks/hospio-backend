package com.hospio.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.maintenance.entity.Repair;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Transitions, due times, board order, schedule dates and who-may (docs/laundry-maintenance.md · Rules). */
class WorkOrderRulesTest {

    private static final Instant T0 = Instant.parse("2026-11-10T08:00:00Z");

    @Test
    void dueTimeFollowsThePriority() {
        assertThat(WorkOrderRules.dueAt(Repair.Priority.URGENT, T0)).isEqualTo(T0.plus(Duration.ofHours(4)));
        assertThat(WorkOrderRules.dueAt(Repair.Priority.HIGH, T0)).isEqualTo(T0.plus(Duration.ofHours(24)));
        assertThat(WorkOrderRules.dueAt(Repair.Priority.NORMAL, T0)).isEqualTo(T0.plus(Duration.ofDays(3)));
        assertThat(WorkOrderRules.dueAt(Repair.Priority.LOW, T0)).isEqualTo(T0.plus(Duration.ofDays(7)));
        assertThat(WorkOrderRules.dueAt(null, T0)).isEqualTo(T0.plus(Duration.ofDays(3)));
    }

    @Test
    void onlyOpenWorkOrdersPastTheirDueTimeAreOverdue() {
        Instant due = T0.plus(Duration.ofHours(4));
        assertThat(WorkOrderRules.overdue(Repair.Status.IN_PROGRESS, due, due.plusSeconds(1))).isTrue();
        assertThat(WorkOrderRules.overdue(Repair.Status.ON_HOLD, due, due.plusSeconds(1))).isTrue();
        assertThat(WorkOrderRules.overdue(Repair.Status.IN_PROGRESS, due, due)).isFalse();
        assertThat(WorkOrderRules.overdue(Repair.Status.DONE, due, due.plusSeconds(60))).isFalse();
        assertThat(WorkOrderRules.overdue(Repair.Status.CANCELLED, due, due.plusSeconds(60))).isFalse();
        assertThat(WorkOrderRules.overdue(Repair.Status.OPEN, null, due)).isFalse();
    }

    @Test
    void theHappyPathIsAllowed() {
        WorkOrderRules.check(WorkOrderRules.Action.ASSIGN, Repair.Status.OPEN, "WO-1");
        WorkOrderRules.check(WorkOrderRules.Action.START, Repair.Status.ASSIGNED, "WO-1");
        WorkOrderRules.check(WorkOrderRules.Action.START, Repair.Status.OPEN, "WO-1");
        WorkOrderRules.check(WorkOrderRules.Action.HOLD, Repair.Status.IN_PROGRESS, "WO-1");
        WorkOrderRules.check(WorkOrderRules.Action.RESUME, Repair.Status.ON_HOLD, "WO-1");
        WorkOrderRules.check(WorkOrderRules.Action.COMPLETE, Repair.Status.IN_PROGRESS, "WO-1");
        WorkOrderRules.check(WorkOrderRules.Action.COMPLETE, Repair.Status.ON_HOLD, "WO-1");
        WorkOrderRules.check(WorkOrderRules.Action.REOPEN, Repair.Status.DONE, "WO-1");
        WorkOrderRules.check(WorkOrderRules.Action.CANCEL, Repair.Status.ON_HOLD, "WO-1");
        WorkOrderRules.check(WorkOrderRules.Action.OUT_OF_ORDER, Repair.Status.ASSIGNED, "WO-1");
    }

    @Test
    void wrongMovesAreRefusedWithASentence() {
        assertThatThrownBy(() -> WorkOrderRules.check(WorkOrderRules.Action.COMPLETE, Repair.Status.ASSIGNED, "WO-2026-00001"))
                .isInstanceOf(BadRequestException.class).hasMessage("Start WO-2026-00001 before completing it");
        assertThatThrownBy(() -> WorkOrderRules.check(WorkOrderRules.Action.HOLD, Repair.Status.ASSIGNED, "WO-1"))
                .hasMessage("Start WO-1 before putting it on hold");
        assertThatThrownBy(() -> WorkOrderRules.check(WorkOrderRules.Action.HOLD, Repair.Status.ON_HOLD, "WO-1")).hasMessage("WO-1 is already on hold");
        assertThatThrownBy(() -> WorkOrderRules.check(WorkOrderRules.Action.START, Repair.Status.ON_HOLD, "WO-1"))
                .hasMessage("WO-1 is on hold · resume it instead");
        assertThatThrownBy(() -> WorkOrderRules.check(WorkOrderRules.Action.START, Repair.Status.IN_PROGRESS, "WO-1")).hasMessage("WO-1 is already in progress");
        assertThatThrownBy(() -> WorkOrderRules.check(WorkOrderRules.Action.RESUME, Repair.Status.IN_PROGRESS, "WO-1")).hasMessage("WO-1 isn't on hold");
        assertThatThrownBy(() -> WorkOrderRules.check(WorkOrderRules.Action.CANCEL, Repair.Status.DONE, "WO-1")).hasMessage("WO-1 is done · reopen it first");
        assertThatThrownBy(() -> WorkOrderRules.check(WorkOrderRules.Action.START, Repair.Status.CANCELLED, "WO-1")).hasMessage("WO-1 is cancelled");
        assertThatThrownBy(() -> WorkOrderRules.check(WorkOrderRules.Action.REOPEN, Repair.Status.IN_PROGRESS, "WO-1"))
                .hasMessage("Only a done work order can be reopened · WO-1 is in progress");
        assertThatThrownBy(() -> WorkOrderRules.check(WorkOrderRules.Action.OUT_OF_ORDER, Repair.Status.DONE, "WO-1"))
                .hasMessage("WO-1 is done · reopen it first");
    }

    @Test
    void reopenedGoesBackToWhoeverHadIt() {
        assertThat(WorkOrderRules.afterReopen("u1")).isEqualTo(Repair.Status.ASSIGNED);
        assertThat(WorkOrderRules.afterReopen(null)).isEqualTo(Repair.Status.OPEN);
    }

    @Test
    void boardShowsOverdueFirstThenUrgentThenSoonestDue() {
        Instant now = T0.plus(Duration.ofDays(1));
        Repair lowOverdue = r("low-overdue", Repair.Priority.LOW, T0, Repair.Status.ASSIGNED);
        Repair urgent = r("urgent", Repair.Priority.URGENT, now.plus(Duration.ofHours(3)), Repair.Status.OPEN);
        Repair normalSoon = r("normal-soon", Repair.Priority.NORMAL, now.plus(Duration.ofHours(1)), Repair.Status.OPEN);
        Repair normalLate = r("normal-late", Repair.Priority.NORMAL, now.plus(Duration.ofHours(30)), Repair.Status.OPEN);
        List<Repair> rows = new ArrayList<>(List.of(normalLate, urgent, normalSoon, lowOverdue));
        rows.sort(WorkOrderRules.boardOrder(now));
        assertThat(rows).extracting(Repair::getTitle).containsExactly("low-overdue", "urgent", "normal-soon", "normal-late");
    }

    @Test
    void scheduleMovesOnByTheIntervalPastToday() {
        LocalDate today = LocalDate.of(2026, 11, 10);
        assertThat(WorkOrderRules.nextDueAfter(today, 90, today)).isEqualTo(today.plusDays(90));
        assertThat(WorkOrderRules.nextDueAfter(today.minusDays(65), 30, today)).isEqualTo(today.plusDays(25));
        assertThat(WorkOrderRules.nextDueAfter(today.minusDays(1), 1, today)).isEqualTo(today.plusDays(1));
        assertThatThrownBy(() -> WorkOrderRules.nextDueAfter(today, 0, today)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void numbersArePerYearAndPadded() {
        assertThat(WorkOrderRules.number(2026, 7)).isEqualTo("WO-2026-00007");
    }

    @Test
    void whoMayRunAWorkOrder() {
        HospioUserDetails tech = user("t1", Map.of(Role.MAINTENANCE, AccessLevel.EDIT));
        HospioUserDetails other = user("t2", Map.of(Role.MAINTENANCE, AccessLevel.EDIT));
        HospioUserDetails boss = user("s1", Map.of(Role.MAINTENANCE, AccessLevel.FULL));
        HospioUserDetails desk = user("d1", Map.of(Role.FRONT_OFFICE, AccessLevel.EDIT));
        HospioUserDetails viewer = user("v1", Map.of(Role.MAINTENANCE, AccessLevel.VIEW));
        HospioUserDetails manager = user("m1", Map.of(Role.MANAGER, AccessLevel.FULL));
        Repair r = r("x", Repair.Priority.NORMAL, T0, Repair.Status.ASSIGNED);
        r.setAssignedTo("t1");
        r.setReportedBy("d1");
        assertThat(WorkOrderAccess.canRun(tech, r)).isTrue();
        assertThat(WorkOrderAccess.canRun(other, r)).isFalse();
        assertThat(WorkOrderAccess.canRun(boss, r)).isTrue();
        assertThat(WorkOrderAccess.canRun(manager, r)).isTrue();
        assertThat(WorkOrderAccess.canRun(desk, r)).isFalse();
        assertThat(WorkOrderAccess.canRun(viewer, r)).isFalse();
        // taking: only for yourself and only when nobody else has it
        assertThat(WorkOrderAccess.canAssign(other, r, "t2")).isFalse();
        r.setAssignedTo(null);
        assertThat(WorkOrderAccess.canAssign(other, r, "t2")).isTrue();
        assertThat(WorkOrderAccess.canAssign(other, r, "t1")).isFalse();
        assertThat(WorkOrderAccess.canAssign(boss, r, "t1")).isTrue();
        // cancel: the raiser before work starts, the supervisor any time
        assertThat(WorkOrderAccess.canCancel(desk, r)).isTrue();
        r.setStatus(Repair.Status.IN_PROGRESS);
        assertThat(WorkOrderAccess.canCancel(desk, r)).isFalse();
        assertThat(WorkOrderAccess.canCancel(boss, r)).isTrue();
        // reopen: the raiser or a manager
        assertThat(WorkOrderAccess.canReopen(desk, r)).isTrue();
        assertThat(WorkOrderAccess.canReopen(tech, r)).isFalse();
        assertThat(WorkOrderAccess.canReopen(manager, r)).isTrue();
        assertThat(WorkOrderAccess.canTakeOutOfOrder(desk)).isFalse();
        assertThat(WorkOrderAccess.canTakeOutOfOrder(tech)).isTrue();
        assertThat(WorkOrderAccess.raiserRole(desk)).isEqualTo("FRONT_OFFICE");
    }

    private static HospioUserDetails user(String id, Map<Role, AccessLevel> roles) {
        return new HospioUserDetails(id, id + "@x.ng", id, "b1", List.of("h1"), roles, "hotel");
    }

    private static Repair r(String title, Repair.Priority p, Instant due, Repair.Status s) {
        Repair r = Repair.builder().title(title).priority(p).dueAt(due).status(s).build();
        r.setCreatedAt(T0);
        return r;
    }

    // ---- housekeeping "Report a problem" ------------------------------------------------------------

    @Test
    void aReportedRoomWithAGuestInIsRefusedInWords() {
        assertThat(WorkOrderRules.guestInRefusal("104", "Ada Obi")).isEqualTo(
                "Room 104 has a guest checked in (Ada Obi) · it can't go out of order · send the report without taking it out of order, or move the guest first");
    }

    @Test
    void theFrontOfficeHearsOfAnUrgentRoomProblemOnceOnly() {
        assertThat(WorkOrderRules.reportedProblemRoles(true, false, "r1")).containsExactly("FRONT_OFFICE");
        // out of order: property-service's own notice tells the front office, housekeeping and managers
        assertThat(WorkOrderRules.reportedProblemRoles(true, true, "r1")).isEmpty();
        assertThat(WorkOrderRules.reportedProblemRoles(false, true, "r1")).isEmpty();
        assertThat(WorkOrderRules.reportedProblemRoles(false, false, "r1")).isEmpty();
        assertThat(WorkOrderRules.reportedProblemRoles(true, false, null)).isEmpty();
    }

    @Test
    void housekeepingWithEditMayTakeAReportedRoomOutOfOrder() {
        assertThat(WorkOrderAccess.canReportOutOfOrder(user("x", Map.of(Role.HOUSEKEEPING, AccessLevel.EDIT)))).isTrue();
        assertThat(WorkOrderAccess.canReportOutOfOrder(user("x", Map.of(Role.HOUSEKEEPING, AccessLevel.VIEW)))).isFalse();
        assertThat(WorkOrderAccess.canReportOutOfOrder(user("x", Map.of(Role.FRONT_OFFICE, AccessLevel.FULL)))).isFalse();
        assertThat(WorkOrderAccess.canReportOutOfOrder(user("x", Map.of(Role.MAINTENANCE, AccessLevel.EDIT)))).isTrue();
    }
}

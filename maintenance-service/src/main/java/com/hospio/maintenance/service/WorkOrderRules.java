package com.hospio.maintenance.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.maintenance.entity.Repair;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The work-order rules of docs/laundry-maintenance.md: transitions, due times, board order, schedule dates. Pure, unit-tested. */
public final class WorkOrderRules {

    private WorkOrderRules() {
    }

    public enum Action { ASSIGN, START, HOLD, RESUME, COMPLETE, REOPEN, CANCEL, OUT_OF_ORDER, UPDATE }

    public static final Set<Repair.Status> OPEN = EnumSet.of(Repair.Status.OPEN, Repair.Status.ASSIGNED, Repair.Status.IN_PROGRESS, Repair.Status.ON_HOLD);

    private static final Map<Action, Set<Repair.Status>> FROM = Map.of(
            Action.ASSIGN, OPEN,
            Action.START, EnumSet.of(Repair.Status.OPEN, Repair.Status.ASSIGNED),
            Action.HOLD, EnumSet.of(Repair.Status.IN_PROGRESS),
            Action.RESUME, EnumSet.of(Repair.Status.ON_HOLD),
            Action.COMPLETE, EnumSet.of(Repair.Status.IN_PROGRESS, Repair.Status.ON_HOLD),
            Action.REOPEN, EnumSet.of(Repair.Status.DONE),
            Action.CANCEL, OPEN,
            Action.OUT_OF_ORDER, OPEN,
            Action.UPDATE, OPEN);

    /** The SLA for a priority: URGENT 4 h, HIGH 24 h, NORMAL 3 days, LOW 7 days. */
    public static Duration sla(Repair.Priority p) {
        return switch (p == null ? Repair.Priority.NORMAL : p) {
            case URGENT -> Duration.ofHours(4);
            case HIGH -> Duration.ofHours(24);
            case NORMAL -> Duration.ofDays(3);
            case LOW -> Duration.ofDays(7);
        };
    }

    public static Instant dueAt(Repair.Priority p, Instant from) {
        return from.plus(sla(p));
    }

    public static boolean overdue(Repair.Status status, Instant dueAt, Instant now) {
        return status != null && OPEN.contains(status) && dueAt != null && now.isAfter(dueAt);
    }

    /** 400 with a sentence when {@code action} can't be done from {@code status}. */
    public static void check(Action action, Repair.Status status, String number) {
        if (FROM.get(action).contains(status)) {
            return;
        }
        String n = number == null ? "This work order" : number;
        if (status == Repair.Status.DONE) {
            throw new BadRequestException(n + " is done · reopen it first");
        }
        if (status == Repair.Status.CANCELLED) {
            throw new BadRequestException(n + " is cancelled");
        }
        String message = switch (action) {
            case START -> status == Repair.Status.ON_HOLD ? n + " is on hold · resume it instead" : n + " is already in progress";
            case HOLD -> status == Repair.Status.ON_HOLD ? n + " is already on hold" : "Start " + n + " before putting it on hold";
            case RESUME -> n + " isn't on hold";
            case COMPLETE -> "Start " + n + " before completing it";
            case REOPEN -> "Only a done work order can be reopened · " + n + " is " + label(status);
            default -> n + " is " + label(status);
        };
        throw new BadRequestException(message);
    }

    /** Where a reopened work order goes: back to whoever had it, else onto the board. */
    public static Repair.Status afterReopen(String assignedTo) {
        return assignedTo == null ? Repair.Status.OPEN : Repair.Status.ASSIGNED;
    }

    private static final Map<Repair.Priority, Integer> RANK = Map.of(Repair.Priority.URGENT, 0, Repair.Priority.HIGH, 1, Repair.Priority.NORMAL, 2,
            Repair.Priority.LOW, 3);

    /** Overdue first, then priority (urgent first), then the soonest due, then the oldest. */
    public static Comparator<Repair> boardOrder(Instant now) {
        return Comparator.<Repair>comparingInt(r -> overdue(r.getStatus(), r.getDueAt(), now) ? 0 : 1)
                .thenComparingInt(r -> RANK.getOrDefault(r.getPriority(), 2))
                .thenComparing(r -> r.getDueAt() == null ? Instant.MAX : r.getDueAt())
                .thenComparing(r -> r.getCreatedAt() == null ? Instant.MAX : r.getCreatedAt());
    }

    /** A schedule's next due date after it has opened a work order for {@code due}: on by the interval until it is after {@code today}. */
    public static LocalDate nextDueAfter(LocalDate due, int everyDays, LocalDate today) {
        if (everyDays < 1) {
            throw new IllegalArgumentException("everyDays must be at least 1");
        }
        LocalDate next = due.plusDays(everyDays);
        while (!next.isAfter(today)) {
            next = next.plusDays(everyDays);
        }
        return next;
    }

    public static String label(Repair.Status s) {
        return s.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    public static String number(int year, int n) {
        return String.format("WO-%d-%05d", year, n);
    }
}

package com.hospio.finance.service;

import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.finance.client.HotelSettings;
import com.hospio.finance.entity.ClockEntry;
import com.hospio.finance.entity.Shift;
import com.hospio.finance.repository.ClockEntryRepository;
import com.hospio.finance.repository.ShiftRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Attendance notices, every 10 minutes for every hotel (docs/workforce.md):
 * <ul>
 *   <li>a rota shift whose start plus the hotel's "mark late after" minutes has passed with nobody clocked in: the person
 *   and the managers are told once;</li>
 *   <li>a clock-in never clocked out: an hour after the shift ends (or 14 hours after clocking in with no shift) the person
 *   and the managers are reminded once; after 24 hours it is closed at the shift's end (or 12 hours after clocking in) with
 *   a note to check the hours.</li>
 * </ul>
 * Each row is changed with a guarded update, so a second run or instance never tells anyone twice.
 */
@Slf4j
@Component
public class AttendanceWatch {

    static final Duration REMIND_AFTER_SHIFT = Duration.ofHours(1);
    static final Duration REMIND_WITHOUT_SHIFT = Duration.ofHours(14);
    static final Duration CLOSE_AFTER = Duration.ofHours(24);
    static final Duration ASSUMED_SHIFT = Duration.ofHours(12);
    static final String AUTO_NOTE = "Closed automatically: no clock-out. Check the hours";

    private final ShiftRepository shifts;
    private final ClockEntryRepository clocks;
    private final HotelSettings settings;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final ZoneId zone = MoneyService.LAGOS;

    public AttendanceWatch(ShiftRepository shifts, ClockEntryRepository clocks, HotelSettings settings, EventPublisher events,
                           PlatformTransactionManager txm) {
        this.shifts = shifts;
        this.clocks = clocks;
        this.settings = settings;
        this.events = events;
        this.tx = new TransactionTemplate(txm);
    }

    @Scheduled(fixedDelayString = "${hospio.attendance.check-ms:600000}", initialDelayString = "${hospio.attendance.initial-delay-ms:120000}")
    public void run() {
        TenantContext.runWith(Tenant.system("attendance-watch"), () -> {
            Instant now = Instant.now();
            int notIn = safe("not in", () -> notIn(now));
            int open = safe("open clock-ins", () -> openClockIns(now));
            if (notIn + open > 0) {
                log.info("Attendance: {} not-in notices, {} open clock-ins reminded or closed", notIn, open);
            }
        });
    }

    // ---- nobody in for a started shift ---------------------------------------------------------------------------

    int notIn(Instant now) {
        LocalDate today = LocalDate.now(zone);
        Map<String, Integer> lateMinutes = new HashMap<>();
        int told = 0;
        for (Shift s : shifts.notInCandidates(today.minusDays(1), today)) {
            int late = lateMinutes.computeIfAbsent(s.getHotelId(), settings::lateMinutes);
            Instant start = Attendance.start(s, zone);
            Instant end = Attendance.end(s, zone);
            if (!notInDue(start, end, late, now)) {
                continue;
            }
            boolean in = !clocks.findByUserIdAndClockInBetween(s.getUserId(), start.minus(Attendance.EARLY), now).isEmpty();
            try {
                Boolean changed = tx.execute(st -> {
                    if (shifts.markNotInNotified(s.getId(), now) != 1) {
                        return false;
                    }
                    if (!in) {
                        String when = s.getStartsAt() + "–" + s.getEndsAt();
                        notice(s.getBusinessId(), s.getHotelId(), "You haven't clocked in", "Your " + when + " shift started at " + s.getStartsAt()
                                + ". Clock in on My shifts, or tell your manager if you can't come in.", "/shifts", List.of(s.getUserId()), List.of());
                        notice(s.getBusinessId(), s.getHotelId(), nz(s.getUserName()) + " hasn't clocked in",
                                "Shift " + when + " · " + late + " min after the start", "/staff", List.of(), List.of("MANAGER"));
                    }
                    return !in;
                });
                if (Boolean.TRUE.equals(changed)) {
                    told++;
                }
            } catch (RuntimeException e) {
                log.warn("Not-in check failed for shift {}: {}", s.getId(), e.getMessage());
            }
        }
        return told;
    }

    /** Past the start plus the late allowance, and the shift hasn't ended (a shift already over is not news any more). */
    static boolean notInDue(Instant start, Instant end, int lateMinutes, Instant now) {
        return now.isAfter(start.plus(Duration.ofMinutes(lateMinutes))) && now.isBefore(end);
    }

    // ---- clocked in, never clocked out ---------------------------------------------------------------------------

    enum OpenAction { NONE, REMIND, CLOSE }

    /** What to do with a clock-in still open: remind once after the shift (or 14 h), close after 24 h. */
    static OpenAction decide(Instant clockIn, Instant shiftEnd, Instant remindedAt, Instant now) {
        if (now.isAfter(clockIn.plus(CLOSE_AFTER))) {
            return OpenAction.CLOSE;
        }
        Instant remindAt = shiftEnd != null ? shiftEnd.plus(REMIND_AFTER_SHIFT) : clockIn.plus(REMIND_WITHOUT_SHIFT);
        return remindedAt == null && now.isAfter(remindAt) ? OpenAction.REMIND : OpenAction.NONE;
    }

    /** Where a forgotten clock-in is closed: at its shift's end, else 12 hours after clocking in. */
    static Instant closeAt(Instant clockIn, Instant shiftEnd) {
        return shiftEnd != null && shiftEnd.isAfter(clockIn) ? shiftEnd : clockIn.plus(ASSUMED_SHIFT);
    }

    int openClockIns(Instant now) {
        int acted = 0;
        for (ClockEntry c : clocks.findByClockOutIsNullAndClockInBefore(now.minus(REMIND_AFTER_SHIFT))) {
            Instant shiftEnd = shiftOf(c).map(s -> Attendance.end(s, zone)).orElse(null);
            OpenAction action = decide(c.getClockIn(), shiftEnd, c.getRemindedAt(), now);
            if (action == OpenAction.NONE) {
                continue;
            }
            String since = WorkforceService.hm(c.getClockIn()) + (LocalDate.ofInstant(c.getClockIn(), zone).equals(LocalDate.ofInstant(now, zone)) ? ""
                    : " on " + java.time.format.DateTimeFormatter.ofPattern("EEE d MMM").withZone(zone).format(c.getClockIn()));
            try {
                Boolean changed = tx.execute(st -> {
                    if (action == OpenAction.REMIND) {
                        if (clocks.markReminded(c.getId(), now) != 1) {
                            return false;
                        }
                        notice(c.getBusinessId(), c.getHotelId(), "You're still clocked in", "Since " + since + ". Clock out on My shifts when you finish.",
                                "/shifts", List.of(c.getUserId()), List.of());
                        notice(c.getBusinessId(), c.getHotelId(), nz(c.getUserName()) + " is still clocked in", "Since " + since + " · past the end of the shift",
                                "/staff", List.of(), List.of("MANAGER"));
                        return true;
                    }
                    Instant out = closeAt(c.getClockIn(), shiftEnd);
                    String note = c.getNote() == null || c.getNote().isBlank() ? AUTO_NOTE : cut(c.getNote() + " · " + AUTO_NOTE, 300);
                    if (clocks.autoClose(c.getId(), out, note, now) != 1) {
                        return false;
                    }
                    String hours = "Clocked in " + since + ", closed at " + WorkforceService.hm(out) + ". Check the hours.";
                    notice(c.getBusinessId(), c.getHotelId(), "Your clock-in was closed for you", hours, "/shifts", List.of(c.getUserId()), List.of());
                    notice(c.getBusinessId(), c.getHotelId(), nz(c.getUserName()) + " never clocked out", hours, "/staff", List.of(), List.of("MANAGER"));
                    return true;
                });
                if (Boolean.TRUE.equals(changed)) {
                    acted++;
                }
            } catch (RuntimeException e) {
                log.warn("Open clock-in check failed for {}: {}", c.getId(), e.getMessage());
            }
        }
        return acted;
    }

    private Optional<Shift> shiftOf(ClockEntry c) {
        LocalDate d = LocalDate.ofInstant(c.getClockIn(), zone);
        return shifts.findByUserIdAndShiftDateBetween(c.getUserId(), d.minusDays(1), d.plusDays(1)).stream()
                .filter(s -> s.getStatus() != Shift.Status.CANCELLED && Attendance.covers(s, c.getClockIn(), zone))
                .min(Comparator.comparing(s -> Attendance.start(s, zone)));
    }

    private void notice(String businessId, String hotelId, String title, String body, String link, List<String> userIds, List<String> roles) {
        NoticeEvent e = NoticeEvent.builder().kind("ATTENDANCE").title(title).body(body).link(link).userIds(userIds).roles(roles).build();
        e.setBusinessId(businessId);
        e.setHotelId(hotelId);
        events.publish(KafkaTopics.NOTICE, e);
    }

    private static int safe(String step, java.util.function.IntSupplier work) {
        try {
            return work.getAsInt();
        } catch (RuntimeException e) {
            log.warn("Attendance check '{}' failed: {}", step, e.getMessage());
            return 0;
        }
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "Someone" : s;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}

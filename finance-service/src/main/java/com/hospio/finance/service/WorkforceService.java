package com.hospio.finance.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.LeaveDecidedEvent;
import com.hospio.common.kafka.events.LeaveRequestedEvent;
import com.hospio.common.kafka.events.ShiftChangeRequestedEvent;
import com.hospio.common.kafka.events.ShiftRequestDecidedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.finance.client.HotelSettings;
import com.hospio.finance.dto.FinanceRequests;
import com.hospio.finance.dto.WorkforceViews;
import com.hospio.finance.entity.ClockEntry;
import com.hospio.finance.entity.LeaveRequest;
import com.hospio.finance.entity.Shift;
import com.hospio.finance.entity.ShiftRequest;
import com.hospio.finance.repository.ClockEntryRepository;
import com.hospio.finance.repository.LeaveRequestRepository;
import com.hospio.finance.repository.ShiftRepository;
import com.hospio.finance.repository.ShiftRequestRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workforce: the rota, swaps and cover, leave, clock in and out (Staff · Shared 1.4, 2.x; Manager · Staff). */
@Service
@RequiredArgsConstructor
public class WorkforceService {

    private final ShiftRepository shifts;
    private final ShiftRequestRepository shiftRequests;
    private final LeaveRequestRepository leaves;
    private final ClockEntryRepository clocks;
    private final EventPublisher events;
    private final HotelSettings settings;
    private final Notifier notifier;

    /** The rota for the range with who is on the clock and how each finished or running shift is going. Cancelled shifts are left out. */
    @Transactional(readOnly = true)
    public List<WorkforceViews.ShiftView> rota(LocalDate from, LocalDate to) {
        String hotelId = TenantContext.requireHotel();
        LocalDate f = from == null ? LocalDate.now(MoneyService.LAGOS) : from;
        LocalDate t = to == null ? f.plusDays(6) : to;
        List<Shift> rows = shifts.findByHotelIdAndShiftDateBetweenOrderByShiftDateAscStartsAtAsc(hotelId, f, t).stream()
                .filter(s -> s.getStatus() != Shift.Status.CANCELLED).toList();
        Set<String> onNow = clocks.findByHotelIdAndClockOutIsNull(hotelId).stream().map(ClockEntry::getUserId).collect(Collectors.toCollection(HashSet::new));
        Map<String, List<ClockEntry>> byUser = clocks.findByHotelIdAndClockInBetweenOrderByClockIn(hotelId,
                f.minusDays(1).atStartOfDay(MoneyService.LAGOS).toInstant(), t.plusDays(2).atStartOfDay(MoneyService.LAGOS).toInstant())
                .stream().collect(Collectors.groupingBy(ClockEntry::getUserId));
        int late = settings.lateMinutes(hotelId);
        Instant now = Instant.now();
        return rows.stream().map(s -> WorkforceViews.ShiftView.of(s, onNow.contains(s.getUserId()),
                Attendance.of(s, byUser.getOrDefault(s.getUserId(), List.of()), now, late, MoneyService.LAGOS))).toList();
    }

    @Transactional(readOnly = true)
    public List<Shift> myShifts() {
        return shifts.findByUserIdAndShiftDateGreaterThanEqualOrderByShiftDateAsc(CurrentUser.require().getUserId(), LocalDate.now(MoneyService.LAGOS)).stream()
                .filter(s -> s.getStatus() != Shift.Status.CANCELLED).toList();
    }

    @Transactional
    public Shift addShift(FinanceRequests.ShiftRequestDto request) {
        requireManager();
        requireTimes(request);
        return shifts.save(Shift.builder().userId(request.userId()).userName(request.userName()).role(request.role().toUpperCase())
                .shiftDate(request.shiftDate()).startsAt(request.startsAt()).endsAt(request.endsAt()).build());
    }

    /** A shift ending at or before its start time runs over midnight (a night shift); the same start and end is refused. */
    private static void requireTimes(FinanceRequests.ShiftRequestDto request) {
        if (request.endsAt().equals(request.startsAt())) {
            throw new BadRequestException("The shift has to end at a different time than it starts");
        }
    }

    @Transactional
    public Shift changeShift(String id, FinanceRequests.ShiftRequestDto request) {
        requireManager();
        Shift s = shift(id);
        if (s.getStatus() == Shift.Status.CANCELLED) {
            throw new BadRequestException("This shift was cancelled");
        }
        requireTimes(request);
        s.setUserId(request.userId());
        s.setUserName(request.userName());
        s.setRole(request.role().toUpperCase());
        s.setShiftDate(request.shiftDate());
        s.setStartsAt(request.startsAt());
        s.setEndsAt(request.endsAt());
        return shifts.save(s);
    }

    @Transactional
    public Shift cancelShift(String id) {
        requireManager();
        Shift s = shift(id);
        if (s.getStatus() == Shift.Status.CANCELLED) {
            throw new BadRequestException("This shift is already cancelled");
        }
        s.setStatus(Shift.Status.CANCELLED);
        return shifts.save(s);
    }

    @Transactional
    public ShiftRequest askToChange(String shiftId, FinanceRequests.ShiftChangeRequest request) {
        HospioUserDetails user = CurrentUser.require();
        Shift s = shift(shiftId);
        if (!s.getUserId().equals(user.getUserId()) && !user.isManagerOrOwner()) {
            throw new ForbiddenException("That isn't your shift");
        }
        ShiftRequest r = shiftRequests.save(ShiftRequest.builder().shiftId(s.getId()).kind(request.kind()).userId(s.getUserId())
                .userName(s.getUserName()).withUserId(request.withUserId()).reason(request.reason()).build());
        events.publish(KafkaTopics.SHIFT_CHANGE_REQUESTED, ShiftChangeRequestedEvent.builder().requestId(r.getId()).staffUserId(s.getUserId())
                .staffName(s.getUserName()).kind(request.kind().name().toLowerCase()).shiftDate(s.getShiftDate()).build());
        return r;
    }

    @Transactional(readOnly = true)
    public List<ShiftRequest> pendingShiftRequests() {
        return shiftRequests.findByHotelIdAndStatusOrderByCreatedAt(TenantContext.requireHotel(), ShiftRequest.Status.PENDING);
    }

    @Transactional
    public ShiftRequest decideShiftRequest(String id, boolean approved) {
        requireManager();
        ShiftRequest r = shiftRequests.findById(id).orElseThrow(() -> new ResourceNotFoundException("Request not found"));
        if (r.getStatus() != ShiftRequest.Status.PENDING) {
            throw new BadRequestException("This request was already " + r.getStatus().name().toLowerCase());
        }
        r.setStatus(approved ? ShiftRequest.Status.APPROVED : ShiftRequest.Status.DECLINED);
        r.setDecidedBy(CurrentUser.require().getUserId());
        shiftRequests.save(r);
        Shift s = shift(r.getShiftId());
        if (approved) {
            if (r.getWithUserId() != null) {
                s.setUserId(r.getWithUserId());
                s.setUserName(notifier.nameOf(r.getWithUserId()).orElse(null));
            }
            s.setStatus(r.getKind() == ShiftRequest.Kind.SWAP ? Shift.Status.SWAPPED : Shift.Status.COVERED);
            shifts.save(s);
        }
        String decision = approved ? "APPROVED" : "DECLINED";
        events.publish(KafkaTopics.SHIFT_REQUEST_DECIDED, ShiftRequestDecidedEvent.builder().requestId(r.getId()).staffUserId(r.getUserId()).staffName(r.getUserName())
                .kind(r.getKind().name()).shiftDate(s.getShiftDate()).decision(decision).build());
        String what = r.getKind() == ShiftRequest.Kind.SWAP ? "swap" : "cover";
        r.setDeliveries(notifier.toUser(r.getUserId(), "Your shift " + what + " request was " + decision.toLowerCase(),
                CurrentUser.require().getFullName() + " " + decision.toLowerCase() + " your request for a " + what + " on your " + s.getShiftDate() + " shift ("
                        + s.getStartsAt() + " to " + s.getEndsAt() + ").", Delivery.Kind.DECISION, r.getId()));
        return r;
    }

    @Transactional
    public LeaveRequest askForLeave(FinanceRequests.LeaveRequestDto request) {
        HospioUserDetails user = CurrentUser.require();
        if (request.toDate().isBefore(request.fromDate())) {
            throw new BadRequestException("Leave has to end after it starts");
        }
        LeaveRequest l = leaves.save(LeaveRequest.builder().userId(user.getUserId()).userName(user.getFullName()).fromDate(request.fromDate())
                .toDate(request.toDate()).reason(request.reason()).build());
        events.publish(KafkaTopics.LEAVE_REQUESTED, LeaveRequestedEvent.builder().requestId(l.getId()).staffUserId(user.getUserId())
                .staffName(user.getFullName()).fromDate(l.getFromDate()).toDate(l.getToDate()).reason(l.getReason()).build());
        return l;
    }

    @Transactional(readOnly = true)
    public List<LeaveRequest> myLeave() {
        return leaves.findByUserIdOrderByFromDateDesc(CurrentUser.require().getUserId());
    }

    @Transactional(readOnly = true)
    public List<LeaveRequest> pendingLeave() {
        return leaves.findByHotelIdAndStatusOrderByCreatedAt(TenantContext.requireHotel(), LeaveRequest.Status.PENDING);
    }

    @Transactional
    public LeaveRequest decideLeave(String id, FinanceRequests.DecisionRequest request) {
        requireManager();
        HospioUserDetails user = CurrentUser.require();
        LeaveRequest l = leaves.findById(id).orElseThrow(() -> new ResourceNotFoundException("Leave request not found"));
        if (l.getStatus() != LeaveRequest.Status.PENDING && l.getStatus() != LeaveRequest.Status.CHANGE_REQUESTED) {
            throw new BadRequestException("This leave request was already " + l.getStatus().name().toLowerCase().replace('_', ' '));
        }
        l.setStatus(switch (request.decision().toUpperCase()) {
            case "APPROVED" -> LeaveRequest.Status.APPROVED;
            case "DECLINED" -> LeaveRequest.Status.DECLINED;
            default -> LeaveRequest.Status.CHANGE_REQUESTED;
        });
        l.setDecidedBy(user.getUserId());
        l.setDecisionNote(request.note());
        leaves.save(l);
        if (l.getStatus() == LeaveRequest.Status.APPROVED) {
            for (Shift s : shifts.findByUserIdAndShiftDateBetween(l.getUserId(), l.getFromDate(), l.getToDate())) {
                if (s.getStatus() != Shift.Status.CANCELLED) {
                    s.setStatus(Shift.Status.CANCELLED);
                    shifts.save(s);
                }
            }
        }
        events.publish(KafkaTopics.LEAVE_DECIDED, LeaveDecidedEvent.builder().requestId(l.getId()).staffUserId(l.getUserId()).staffName(l.getUserName())
                .fromDate(l.getFromDate()).toDate(l.getToDate()).decision(l.getStatus().name()).note(request.note()).build());
        String verdict = switch (l.getStatus()) {
            case APPROVED -> "approved";
            case DECLINED -> "declined";
            default -> "asked you to change";
        };
        l.setDeliveries(notifier.toUser(l.getUserId(), "Your leave request was " + (l.getStatus() == LeaveRequest.Status.CHANGE_REQUESTED ? "sent back" : verdict),
                user.getFullName() + " " + verdict + " your leave from " + l.getFromDate() + " to " + l.getToDate() + " (" + l.getDays() + " day" + (l.getDays() == 1 ? "" : "s") + ")."
                        + (request.note() == null || request.note().isBlank() ? "" : " " + request.note()), Delivery.Kind.DECISION, l.getId()));
        return l;
    }

    @Transactional
    public ClockEntry clockIn(FinanceRequests.ClockRequest request) {
        HospioUserDetails user = CurrentUser.require();
        clocks.findFirstByUserIdAndClockOutIsNullOrderByClockInDesc(user.getUserId()).ifPresent(c -> {
            throw new BadRequestException("You're already clocked in");
        });
        return clocks.save(ClockEntry.builder().userId(user.getUserId()).userName(user.getFullName()).note(request == null ? null : request.note()).build());
    }

    @Transactional
    public ClockEntry clockOut(FinanceRequests.ClockRequest request) {
        HospioUserDetails user = CurrentUser.require();
        ClockEntry c = clocks.findFirstByUserIdAndClockOutIsNullOrderByClockInDesc(user.getUserId())
                .orElseThrow(() -> new BadRequestException("You're not clocked in"));
        c.setClockOut(Instant.now());
        if (request != null && request.note() != null) {
            c.setNote(request.note());
        }
        return clocks.save(c);
    }

    /** Am I on the clock, since when, and how many minutes have I worked today (Lagos day). */
    @Transactional(readOnly = true)
    public WorkforceViews.ClockStatus myClock() {
        String userId = CurrentUser.require().getUserId();
        Instant dayStart = LocalDate.now(MoneyService.LAGOS).atStartOfDay(MoneyService.LAGOS).toInstant();
        var open = clocks.findFirstByUserIdAndClockOutIsNullOrderByClockInDesc(userId);
        List<ClockEntry> today = new java.util.ArrayList<>(clocks.findByUserIdAndClockInGreaterThanEqualOrderByClockIn(userId, dayStart));
        open.filter(o -> today.stream().noneMatch(t -> t.getId().equals(o.getId()))).ifPresent(today::add);
        return new WorkforceViews.ClockStatus(open.isPresent(), open.map(ClockEntry::getClockIn).orElse(null), Attendance.minutesWorked(today, dayStart, Instant.now()));
    }

    /** Everyone's clock entries that touch the day (who is on, since when), plus anyone still clocked in from before. */
    @Transactional(readOnly = true)
    public List<WorkforceViews.ClockView> clockEntries(LocalDate date) {
        String hotelId = TenantContext.requireHotel();
        LocalDate d = date == null ? LocalDate.now(MoneyService.LAGOS) : date;
        Map<String, ClockEntry> byId = new java.util.LinkedHashMap<>();
        clocks.findByHotelIdAndClockInBetweenOrderByClockIn(hotelId, d.atStartOfDay(MoneyService.LAGOS).toInstant(), d.plusDays(1).atStartOfDay(MoneyService.LAGOS).toInstant())
                .forEach(c -> byId.put(c.getId(), c));
        if (!d.isBefore(LocalDate.now(MoneyService.LAGOS))) {
            clocks.findByHotelIdAndClockOutIsNull(hotelId).forEach(c -> byId.putIfAbsent(c.getId(), c));
        }
        return byId.values().stream().map(c -> new WorkforceViews.ClockView(c.getId(), c.getUserId(), c.getUserName(), c.getClockIn(), c.getClockOut(),
                c.getClockOut() == null, c.getNote())).toList();
    }

    private Shift shift(String id) {
        return shifts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Shift not found"));
    }

    private static void requireManager() {
        HospioUserDetails user = CurrentUser.require();
        if (user.isReadOnly() || (!user.isManagerOrOwner() && !user.isPlatform())) {
            throw new ForbiddenException("Only a manager can change the rota");
        }
    }
}

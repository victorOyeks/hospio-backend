package com.hospio.finance.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.LeaveRequestedEvent;
import com.hospio.common.kafka.events.ShiftChangeRequestedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.finance.dto.FinanceRequests;
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
import java.util.List;
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

    @Transactional(readOnly = true)
    public List<Shift> rota(LocalDate from, LocalDate to) {
        LocalDate f = from == null ? LocalDate.now() : from;
        return shifts.findByHotelIdAndShiftDateBetweenOrderByShiftDateAscStartsAtAsc(TenantContext.requireHotel(), f, to == null ? f.plusDays(6) : to);
    }

    @Transactional(readOnly = true)
    public List<Shift> myShifts() {
        return shifts.findByUserIdAndShiftDateGreaterThanEqualOrderByShiftDateAsc(CurrentUser.require().getUserId(), LocalDate.now());
    }

    @Transactional
    public Shift addShift(FinanceRequests.ShiftRequestDto request) {
        requireManager();
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new BadRequestException("The shift has to end after it starts");
        }
        return shifts.save(Shift.builder().userId(request.userId()).userName(request.userName()).role(request.role().toUpperCase())
                .shiftDate(request.shiftDate()).startsAt(request.startsAt()).endsAt(request.endsAt()).build());
    }

    @Transactional
    public Shift changeShift(String id, FinanceRequests.ShiftRequestDto request) {
        requireManager();
        Shift s = shift(id);
        s.setUserId(request.userId());
        s.setUserName(request.userName());
        s.setRole(request.role().toUpperCase());
        s.setShiftDate(request.shiftDate());
        s.setStartsAt(request.startsAt());
        s.setEndsAt(request.endsAt());
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
        r.setStatus(approved ? ShiftRequest.Status.APPROVED : ShiftRequest.Status.DECLINED);
        r.setDecidedBy(CurrentUser.require().getUserId());
        shiftRequests.save(r);
        if (approved) {
            Shift s = shift(r.getShiftId());
            if (r.getWithUserId() != null) {
                s.setUserId(r.getWithUserId());
                s.setUserName(null);
            }
            s.setStatus(r.getKind() == ShiftRequest.Kind.SWAP ? Shift.Status.SWAPPED : Shift.Status.COVERED);
            shifts.save(s);
        }
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
        LeaveRequest l = leaves.findById(id).orElseThrow(() -> new ResourceNotFoundException("Leave request not found"));
        l.setStatus(switch (request.decision().toUpperCase()) {
            case "APPROVED" -> LeaveRequest.Status.APPROVED;
            case "DECLINED" -> LeaveRequest.Status.DECLINED;
            default -> LeaveRequest.Status.CHANGE_REQUESTED;
        });
        l.setDecidedBy(CurrentUser.require().getUserId());
        l.setDecisionNote(request.note());
        return leaves.save(l);
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

    private Shift shift(String id) {
        return shifts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Shift not found"));
    }

    private static void requireManager() {
        if (!CurrentUser.require().isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager can change the rota");
        }
    }
}

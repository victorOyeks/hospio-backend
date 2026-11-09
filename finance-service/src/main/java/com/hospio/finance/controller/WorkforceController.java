package com.hospio.finance.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.finance.dto.FinanceRequests;
import com.hospio.finance.dto.WorkforceViews;
import com.hospio.finance.entity.ClockEntry;
import com.hospio.finance.entity.LeaveRequest;
import com.hospio.finance.entity.Shift;
import com.hospio.finance.entity.ShiftRequest;
import com.hospio.finance.service.WorkforceService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff · Shared 1.4, 2.1-2.5 and Manager · Staff (rota, find cover, leave). */
@RestController
@RequestMapping("/workforce")
@RequiredArgsConstructor
public class WorkforceController {

    private static final String READ = "@access.managerView() or @access.atLeast('FINANCE','VIEW')";

    private final WorkforceService service;

    @GetMapping("/rota")
    @PreAuthorize(READ)
    public ApiResponse<List<WorkforceViews.ShiftView>> rota(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.rota(from, to));
    }

    @GetMapping("/shifts/mine")
    public ApiResponse<List<Shift>> myShifts() {
        return ApiResponse.ok(service.myShifts());
    }

    @PostMapping("/shifts")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Shift> addShift(@Valid @RequestBody FinanceRequests.ShiftRequestDto request) {
        return ApiResponse.ok("Shift added", service.addShift(request));
    }

    @PutMapping("/shifts/{id}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Shift> changeShift(@PathVariable String id, @Valid @RequestBody FinanceRequests.ShiftRequestDto request) {
        return ApiResponse.ok("Shift changed", service.changeShift(id, request));
    }

    /** Cancels the shift (status CANCELLED); it drops off the rota. */
    @DeleteMapping("/shifts/{id}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Shift> cancelShift(@PathVariable String id) {
        return ApiResponse.ok("Shift cancelled", service.cancelShift(id));
    }

    @PostMapping("/shifts/{id}/requests")
    public ApiResponse<ShiftRequest> askToChange(@PathVariable String id, @Valid @RequestBody FinanceRequests.ShiftChangeRequest request) {
        return ApiResponse.ok("Request sent to your manager", service.askToChange(id, request));
    }

    @GetMapping("/shift-requests")
    @PreAuthorize(READ)
    public ApiResponse<List<ShiftRequest>> pendingShiftRequests() {
        return ApiResponse.ok(service.pendingShiftRequests());
    }

    @PostMapping("/shift-requests/{id}/decide")
    @PreAuthorize("@access.manager()")
    public ApiResponse<ShiftRequest> decideShiftRequest(@PathVariable String id, @RequestParam boolean approved) {
        return ApiResponse.ok(approved ? "Approved" : "Declined", service.decideShiftRequest(id, approved));
    }

    @PostMapping("/leave")
    public ApiResponse<LeaveRequest> askForLeave(@Valid @RequestBody FinanceRequests.LeaveRequestDto request) {
        return ApiResponse.ok("Leave request sent", service.askForLeave(request));
    }

    @GetMapping("/leave/mine")
    public ApiResponse<List<LeaveRequest>> myLeave() {
        return ApiResponse.ok(service.myLeave());
    }

    @GetMapping("/leave/pending")
    @PreAuthorize(READ)
    public ApiResponse<List<LeaveRequest>> pendingLeave() {
        return ApiResponse.ok(service.pendingLeave());
    }

    @PostMapping("/leave/{id}/decide")
    @PreAuthorize("@access.manager()")
    public ApiResponse<LeaveRequest> decideLeave(@PathVariable String id, @Valid @RequestBody FinanceRequests.DecisionRequest request) {
        return ApiResponse.ok("Decided", service.decideLeave(id, request));
    }

    @PostMapping("/clock-in")
    public ApiResponse<ClockEntry> clockIn(@RequestBody(required = false) FinanceRequests.ClockRequest request) {
        return ApiResponse.ok("Clocked in", service.clockIn(request));
    }

    @PostMapping("/clock-out")
    public ApiResponse<ClockEntry> clockOut(@RequestBody(required = false) FinanceRequests.ClockRequest request) {
        return ApiResponse.ok("Clocked out", service.clockOut(request));
    }

    /** Am I clocked in, since when, and how many minutes today. */
    @GetMapping("/clock/me")
    public ApiResponse<WorkforceViews.ClockStatus> myClock() {
        return ApiResponse.ok(service.myClock());
    }

    /** Who is on the clock (and who was) on the day. */
    @GetMapping("/clock")
    @PreAuthorize(READ)
    public ApiResponse<List<WorkforceViews.ClockView>> clock(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(service.clockEntries(date));
    }
}

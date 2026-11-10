package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.platform.dto.AdminResponses.ChangeRow;
import com.hospio.platform.dto.OwnerRequests;
import com.hospio.platform.entity.Report;
import com.hospio.platform.entity.SupportTicket;
import com.hospio.platform.service.OwnerRequestService;
import com.hospio.platform.service.ReportScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Owner portal (docs/owner-portal.md): asking H.M.S support to undo a change or transfer ownership, and report schedules. */
@RestController
@RequiredArgsConstructor
public class OwnerPortalController {

    private final OwnerRequestService requests;
    private final ReportScheduleService schedules;

    @Operation(summary = "Owner: ask H.M.S support to undo one of its changes within 7 days (opens a ticket)")
    @PostMapping("/changes/{id}/request-undo")
    @PreAuthorize("@access.owner()")
    public ApiResponse<ChangeRow> requestUndo(@PathVariable String id, @Valid @RequestBody(required = false) OwnerRequests.UndoRequest request) {
        return ApiResponse.ok("Hospio support has your request", requests.requestUndo(id, request));
    }

    @Operation(summary = "Owner: ask H.M.S support to hand the business to a new owner (opens a ticket)")
    @PostMapping("/support/ownership-transfer")
    @PreAuthorize("@access.owner()")
    public ApiResponse<SupportTicket> requestTransfer(@Valid @RequestBody OwnerRequests.OwnershipTransfer request) {
        return ApiResponse.ok("Transfer request sent to H.M.S support", requests.requestTransfer(request));
    }

    @Operation(summary = "Owner: the latest ownership transfer request, if any")
    @GetMapping("/support/ownership-transfer")
    @PreAuthorize("@access.owner()")
    public ApiResponse<SupportTicket> currentTransfer() {
        return ApiResponse.ok(requests.currentTransfer().orElse(null));
    }

    @Operation(summary = "Schedule a report (owner: any hotel or all, optionally to the managers; manager: their hotel)")
    @PostMapping("/reports/schedules")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Report> schedule(@Valid @RequestBody OwnerRequests.ReportSchedule request) {
        return ApiResponse.ok("Report scheduled", schedules.schedule(request));
    }

    @PostMapping("/reports/schedules/{id}/stop")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Report> stop(@PathVariable String id) {
        return ApiResponse.ok("Schedule stopped", schedules.stop(id));
    }

    @Operation(summary = "Send a scheduled report's latest full period now; on staging the email is echoed back")
    @PostMapping("/reports/schedules/{id}/run")
    @PreAuthorize("@access.manager()")
    public ApiResponse<ReportScheduleService.Run> runNow(@PathVariable String id) {
        return ApiResponse.ok("Sent", schedules.runNow(id));
    }
}

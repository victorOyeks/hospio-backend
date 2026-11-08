package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.response.PagedResponse;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.ActivityEntry;
import com.hospio.platform.entity.PlatformChange;
import com.hospio.platform.entity.Report;
import com.hospio.platform.entity.SupportTicket;
import com.hospio.platform.service.ActivityLogService;
import com.hospio.platform.service.DepartmentsLiveService;
import com.hospio.platform.service.PlatformAdminService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Activity log, Departments live, reports, support tickets and Changes by H.M.S, for managers and owners. */
@RestController
@RequiredArgsConstructor
public class InsightsController {

    private final ActivityLogService activity;
    private final DepartmentsLiveService live;
    private final PlatformAdminService admin;

    @GetMapping("/activity")
    public ApiResponse<PagedResponse<ActivityEntry>> activity(@RequestParam(required = false) String businessId,
                                                              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
                                                              @RequestParam(defaultValue = "false") boolean byPlatform) {
        return ApiResponse.ok(PagedResponse.of(activity.list(businessId, page, size, byPlatform), e -> e));
    }

    @GetMapping("/activity/{id}")
    public ApiResponse<ActivityEntry> entry(@PathVariable String id) {
        return ApiResponse.ok(activity.get(id));
    }

    @GetMapping("/departments/live")
    public ApiResponse<Map<String, Map<String, Object>>> departmentsLive() {
        return ApiResponse.ok(live.board());
    }

    @GetMapping("/reports")
    public ApiResponse<List<Report>> reports() {
        return ApiResponse.ok(admin.reports());
    }

    @PostMapping("/reports")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Report> requestReport(@Valid @RequestBody PlatformRequests.ReportRequest request) {
        return ApiResponse.ok(request.schedule() == null ? "Report ready" : "Report scheduled", admin.requestReport(request));
    }

    @GetMapping("/support/tickets")
    public ApiResponse<List<SupportTicket>> tickets(@RequestParam(required = false) String status) {
        return ApiResponse.ok(admin.tickets(status));
    }

    @PostMapping("/support/tickets")
    public ApiResponse<SupportTicket> openTicket(@Valid @RequestBody PlatformRequests.TicketRequest request) {
        return ApiResponse.ok("Ticket opened", admin.openTicket(request));
    }

    @GetMapping("/changes")
    public ApiResponse<List<PlatformChange>> changes(@RequestParam(required = false) String businessId) {
        return ApiResponse.ok(admin.changes(businessId));
    }

    @PostMapping("/changes/{id}/undo")
    @PreAuthorize("@access.owner()")
    public ApiResponse<PlatformChange> undo(@PathVariable String id) {
        return ApiResponse.ok("Change undone", admin.undo(id));
    }
}

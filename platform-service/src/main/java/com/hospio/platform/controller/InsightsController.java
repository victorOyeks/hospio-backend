package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.response.PagedResponse;
import com.hospio.platform.dto.AdminResponses.ChangeRow;
import com.hospio.platform.dto.AdminResponses.TicketDetail;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.ActivityEntry;
import com.hospio.platform.entity.PlatformChange;
import com.hospio.platform.entity.Report;
import com.hospio.platform.entity.SupportTicket;
import com.hospio.platform.service.ActivityLogService;
import com.hospio.platform.service.ChangeService;
import com.hospio.platform.service.DepartmentsLiveService;
import com.hospio.platform.service.PlatformAdminService;
import com.hospio.platform.service.SupportService;
import io.swagger.v3.oas.annotations.Operation;
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
    private final SupportService support;
    private final ChangeService changes;

    /**
     * The activity log (docs/manager-leftovers.md): newest first, paged (size 1 to 200), filtered by {@code q} (summary, person, event),
     * {@code department}, {@code from}/{@code to} (Lagos days) and {@code byPlatform}. Managers, the owner and H.M.S only.
     */
    @GetMapping("/activity")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<PagedResponse<ActivityEntry>> activity(@RequestParam(required = false) String businessId,
                                                              @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                                              @RequestParam(defaultValue = "false") boolean byPlatform,
                                                              @RequestParam(required = false) String q, @RequestParam(required = false) String department,
                                                              @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
                                                              @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return ApiResponse.ok(PagedResponse.of(activity.list(new ActivityLogService.Filter(businessId, q, department, from, to, byPlatform), page, size),
                e -> e));
    }

    @GetMapping("/activity/{id}")
    @PreAuthorize("@access.managerView()")
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

    @Operation(summary = "Hotel users: the tickets of their business (status filter optional); the platform gets the open ones")
    @GetMapping("/support/tickets")
    public ApiResponse<List<SupportTicket>> tickets(@RequestParam(required = false) String status) {
        return ApiResponse.ok(support.mine(status));
    }

    @Operation(summary = "Hotel users: one of their tickets with its non-internal comments")
    @GetMapping("/support/tickets/{id}")
    public ApiResponse<TicketDetail> ticket(@PathVariable String id) {
        return ApiResponse.ok(support.get(id));
    }

    @Operation(summary = "Hotel users: open a ticket for their business")
    @PostMapping("/support/tickets")
    public ApiResponse<SupportTicket> openTicket(@Valid @RequestBody PlatformRequests.TicketRequest request) {
        return ApiResponse.ok("Ticket opened", support.open(request));
    }

    @Operation(summary = "Changes H.M.S made in the caller's business (platform names a businessId)")
    @GetMapping("/changes")
    public ApiResponse<List<ChangeRow>> changes(@RequestParam(required = false) String businessId) {
        return ApiResponse.ok(changes.forCaller(businessId));
    }

    @Operation(summary = "Undo a change by H.M.S within 7 days: the business's owner or the platform")
    @PostMapping("/changes/{id}/undo")
    @PreAuthorize("@access.owner() or @access.platform()")
    public ApiResponse<PlatformChange> undo(@PathVariable String id) {
        return ApiResponse.ok("Change undone", changes.undo(id));
    }
}

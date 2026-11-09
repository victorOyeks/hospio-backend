package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.response.PagedResponse;
import com.hospio.platform.dto.AdminResponses.ChangeRow;
import com.hospio.platform.dto.AdminResponses.HotelOverview;
import com.hospio.platform.dto.AdminResponses.Overview;
import com.hospio.platform.dto.AdminResponses.Usage;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.Alert;
import com.hospio.platform.entity.HealthScore;
import com.hospio.platform.entity.PlatformChange;
import com.hospio.platform.service.AlertService;
import com.hospio.platform.service.ChangeService;
import com.hospio.platform.service.HealthService;
import com.hospio.platform.service.OverviewService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard, business tabs (Usage, Hotels), health and alerts (L7), changes by H.M.S (L1-L3). Platform only. */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@PreAuthorize("@access.platform()")
public class AdminOverviewController {

    private final OverviewService overview;
    private final HealthService health;
    private final AlertService alerts;
    private final ChangeService changes;

    @Operation(summary = "Dashboard: businesses by stage, hotels, rooms, this month's fees, due and overdue, open tickets, onboarding, alerts, recent changes and activity, health buckets")
    @GetMapping("/overview")
    public ApiResponse<Overview> overview() {
        return ApiResponse.ok(overview.overview());
    }

    @Operation(summary = "Usage tab: active people 7/30 days, bookings, payments and messages per week for 8 weeks, adoption flags")
    @GetMapping("/businesses/{id}/usage")
    public ApiResponse<Usage> usage(@PathVariable String id) {
        return ApiResponse.ok(overview.usage(id));
    }

    @Operation(summary = "Hotels tab: one hotel's rooms, occupancy, arrivals, departures, open folios, payments today and manager, gathered from each service")
    @GetMapping("/businesses/{id}/hotels/{hotelId}/overview")
    public ApiResponse<HotelOverview> hotelOverview(@PathVariable String id, @PathVariable String hotelId) {
        return ApiResponse.ok(overview.hotelOverview(id, hotelId));
    }

    @Operation(summary = "Latest health score per business, or the one business named")
    @GetMapping("/health")
    public ApiResponse<List<HealthScore>> health(@RequestParam(required = false) String businessId) {
        return ApiResponse.ok(health.list(businessId));
    }

    @Operation(summary = "Recompute a business's health score now")
    @PostMapping("/health/{businessId}")
    public ApiResponse<HealthScore> computeHealth(@PathVariable String businessId) {
        return ApiResponse.ok(health.compute(businessId));
    }

    @Operation(summary = "Alerts, newest first; open=true for the open ones only, businessId to narrow")
    @GetMapping("/alerts")
    public ApiResponse<List<Alert>> alerts(@RequestParam(required = false) String businessId, @RequestParam(defaultValue = "true") boolean open) {
        return ApiResponse.ok(alerts.list(businessId, open));
    }

    @Operation(summary = "Dismiss an alert")
    @PostMapping("/alerts/{id}/dismiss")
    public ApiResponse<Alert> dismiss(@PathVariable String id) {
        return ApiResponse.ok("Dismissed", alerts.dismiss(id));
    }

    @Operation(summary = "Changes made as H.M.S, paged, by business, admin and date range, with names and whether undone")
    @GetMapping("/changes")
    public ApiResponse<PagedResponse<ChangeRow>> changes(@RequestParam(required = false) String businessId, @RequestParam(required = false) String madeBy,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(changes.search(businessId, madeBy, from, to, page, size));
    }

    @Operation(summary = "Record a change made as H.M.S: area, description, reason (8+ chars), before and after; the owner can undo it for 7 days")
    @PostMapping("/changes")
    public ApiResponse<PlatformChange> recordChange(@Valid @RequestBody PlatformRequests.PlatformChangeRequest request) {
        return ApiResponse.ok("Change recorded; the owner has been told", changes.record(request));
    }
}

package com.hospio.maintenance.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.maintenance.dto.MaintenanceRequests;
import com.hospio.maintenance.dto.MaintenanceViews;
import com.hospio.maintenance.entity.ScheduledJobRun;
import com.hospio.maintenance.service.MaintenanceService;
import com.hospio.maintenance.service.RepairLists;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Maintenance 1.x-4.x and Manager · Tasks: work orders, rooms out of order, assets and preventive schedules (docs/laundry-maintenance.md). */
@RestController
@RequiredArgsConstructor
public class MaintenanceController {

    /** Raisers and readers of work orders: the desk, housekeeping, maintenance, managers and owners. */
    static final String READ = "@access.managerView() or @access.has('MAINTENANCE') or @access.has('FRONT_OFFICE') or @access.has('HOUSEKEEPING')";
    static final String RAISE = "@access.manager() or @access.atLeast('MAINTENANCE','EDIT') or @access.atLeast('FRONT_OFFICE','EDIT') "
            + "or @access.atLeast('HOUSEKEEPING','EDIT')";
    static final String RUN = "@access.manager() or @access.atLeast('MAINTENANCE','EDIT')";
    static final String MAINT_READ = "@access.managerView() or @access.has('MAINTENANCE')";
    static final String MONEY = "@access.managerView() or @access.has('FINANCE') or @access.atLeast('MAINTENANCE','FULL')";

    private final MaintenanceService service;
    private final RepairLists lists;

    @GetMapping("/repairs")
    @PreAuthorize(READ)
    public ApiResponse<?> list(@RequestParam(required = false) String scope, @RequestParam(required = false) String roomId,
                               @RequestParam(required = false) String q,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(required = false) String sort, @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size) {
        if (!com.hospio.common.paging.Paging.requested(page, size)) {
            // Unpaged (internal callers, scripts and the open board, which is bounded by nature): every match, no cap.
            return ApiResponse.ok(service.list(scope, roomId));
        }
        return ApiResponse.ok(lists.page(new RepairLists.Query(scope, roomId, q, from, to, sort, page, size)));
    }

    @GetMapping("/repairs/summary")
    @PreAuthorize(MONEY)
    public ApiResponse<MaintenanceViews.Summary> summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.summary(from, to));
    }

    @GetMapping("/repairs/{id}")
    @PreAuthorize(READ)
    public ApiResponse<MaintenanceViews.RepairView> get(@PathVariable String id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/repairs")
    @PreAuthorize(RAISE)
    public ApiResponse<MaintenanceViews.RepairView> open(@Valid @RequestBody MaintenanceRequests.RepairRequest request) {
        return ApiResponse.ok("Work order raised", service.open(request, false));
    }

    @PostMapping("/repairs/{id}/assign")
    @PreAuthorize(RUN)
    public ApiResponse<MaintenanceViews.RepairView> assign(@PathVariable String id, @Valid @RequestBody MaintenanceRequests.AssignRequest request) {
        return ApiResponse.ok("Assigned", service.assign(id, request));
    }

    @PostMapping("/repairs/{id}/start")
    @PreAuthorize(RUN)
    public ApiResponse<MaintenanceViews.RepairView> start(@PathVariable String id) {
        return ApiResponse.ok("Started", service.start(id));
    }

    @PostMapping("/repairs/{id}/hold")
    @PreAuthorize(RUN)
    public ApiResponse<MaintenanceViews.RepairView> hold(@PathVariable String id, @RequestBody(required = false) MaintenanceRequests.ReasonRequest request) {
        return ApiResponse.ok("On hold", service.hold(id, request));
    }

    @PostMapping("/repairs/{id}/resume")
    @PreAuthorize(RUN)
    public ApiResponse<MaintenanceViews.RepairView> resume(@PathVariable String id) {
        return ApiResponse.ok("Back in progress", service.resume(id));
    }

    @PostMapping("/repairs/{id}/complete")
    @PreAuthorize(RUN)
    public ApiResponse<MaintenanceViews.RepairView> complete(@PathVariable String id, @RequestBody(required = false) MaintenanceRequests.CompleteRequest request) {
        return ApiResponse.ok("Done", service.complete(id, request));
    }

    @PostMapping("/repairs/{id}/reopen")
    @PreAuthorize(RAISE)
    public ApiResponse<MaintenanceViews.RepairView> reopen(@PathVariable String id, @RequestBody(required = false) MaintenanceRequests.ReasonRequest request) {
        return ApiResponse.ok("Reopened", service.reopen(id, request));
    }

    @PostMapping("/repairs/{id}/cancel")
    @PreAuthorize(RAISE)
    public ApiResponse<MaintenanceViews.RepairView> cancel(@PathVariable String id, @RequestBody(required = false) MaintenanceRequests.ReasonRequest request) {
        return ApiResponse.ok("Cancelled", service.cancel(id, request));
    }

    @PostMapping("/repairs/{id}/updates")
    @PreAuthorize(RAISE)
    public ApiResponse<MaintenanceViews.RepairView> addUpdate(@PathVariable String id, @Valid @RequestBody MaintenanceRequests.UpdateRequest request) {
        return ApiResponse.ok("Update added", service.addUpdate(id, request));
    }

    @PostMapping("/repairs/{id}/out-of-order")
    @PreAuthorize(RUN)
    public ApiResponse<MaintenanceViews.RepairView> outOfOrder(@PathVariable String id,
                                                               @RequestBody(required = false) MaintenanceRequests.OutOfOrderRequest request) {
        return ApiResponse.ok("Room out of order", service.outOfOrder(id, request));
    }

    @PostMapping("/repairs/{id}/back-in-service")
    @PreAuthorize(RUN)
    public ApiResponse<MaintenanceViews.RepairView> backInService(@PathVariable String id) {
        return ApiResponse.ok("Room back in service", service.backInService(id));
    }

    @PostMapping(value = "/repairs/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(RAISE)
    public ApiResponse<MaintenanceViews.RepairView> uploadPhoto(@PathVariable String id, @RequestPart(name = "file", required = false) MultipartFile file) {
        return ApiResponse.ok("Photo added", service.photo(id, file));
    }

    @GetMapping("/repairs/{id}/photo")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> photo(@PathVariable String id) {
        MaintenanceService.Photo p = service.photo(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(p.contentType())).cacheControl(CacheControl.noStore()).body(p.bytes());
    }

    @GetMapping("/assets")
    @PreAuthorize(MAINT_READ)
    public ApiResponse<List<MaintenanceViews.AssetView>> assets() {
        return ApiResponse.ok(service.assets());
    }

    @PostMapping("/assets")
    @PreAuthorize(RUN)
    public ApiResponse<MaintenanceViews.AssetView> addAsset(@RequestBody MaintenanceRequests.AssetRequest request) {
        return ApiResponse.ok("Asset added", service.saveAsset(null, request));
    }

    @PutMapping("/assets/{id}")
    @PreAuthorize(RUN)
    public ApiResponse<MaintenanceViews.AssetView> saveAsset(@PathVariable String id, @RequestBody MaintenanceRequests.AssetRequest request) {
        return ApiResponse.ok("Saved", service.saveAsset(id, request));
    }

    @GetMapping("/scheduled-jobs")
    @PreAuthorize(MAINT_READ)
    public ApiResponse<List<MaintenanceViews.JobView>> scheduledJobs() {
        return ApiResponse.ok(service.scheduledJobs());
    }

    @PostMapping("/scheduled-jobs")
    @PreAuthorize(RUN)
    public ApiResponse<MaintenanceViews.JobView> addJob(@RequestBody MaintenanceRequests.ScheduledJobRequest request) {
        return ApiResponse.ok("Schedule added", service.saveJob(null, request));
    }

    @PostMapping("/scheduled-jobs/run")
    @PreAuthorize("@access.manager()")
    public ApiResponse<MaintenanceViews.RunResult> runNow() {
        MaintenanceViews.RunResult r = service.runDueNow();
        return ApiResponse.ok(r.opened() == 0 ? "Nothing is due" : r.opened() + " work order" + (r.opened() == 1 ? "" : "s") + " opened", r);
    }

    @PutMapping("/scheduled-jobs/{id}")
    @PreAuthorize(RUN)
    public ApiResponse<MaintenanceViews.JobView> updateJob(@PathVariable String id, @RequestBody MaintenanceRequests.ScheduledJobRequest request) {
        return ApiResponse.ok("Saved", service.saveJob(id, request));
    }

    @GetMapping("/scheduled-jobs/{id}/runs")
    @PreAuthorize(MAINT_READ)
    public ApiResponse<List<ScheduledJobRun>> pastRuns(@PathVariable String id) {
        return ApiResponse.ok(service.pastRuns(id));
    }

    @PutMapping("/scheduled-jobs/{id}/pause")
    @PreAuthorize(RUN)
    public ApiResponse<MaintenanceViews.JobView> pause(@PathVariable String id, @RequestParam(defaultValue = "true") boolean paused) {
        return ApiResponse.ok(paused ? "Paused" : "Resumed", service.pauseJob(id, paused));
    }

    @DeleteMapping("/scheduled-jobs/{id}")
    @PreAuthorize(RUN)
    public ApiResponse<Void> deleteJob(@PathVariable String id) {
        service.deleteJob(id);
        return ApiResponse.ok("Deleted", null);
    }
}

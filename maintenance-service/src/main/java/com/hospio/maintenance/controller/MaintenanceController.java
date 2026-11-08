package com.hospio.maintenance.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.maintenance.dto.MaintenanceRequests;
import com.hospio.maintenance.entity.PartRequest;
import com.hospio.maintenance.entity.Repair;
import com.hospio.maintenance.entity.RepairUpdate;
import com.hospio.maintenance.entity.ScheduledJob;
import com.hospio.maintenance.entity.ScheduledJobRun;
import com.hospio.maintenance.entity.SparePart;
import com.hospio.maintenance.service.MaintenanceService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Maintenance 1.1-4.2 and Manager · Tasks (maintenance schedule). */
@RestController
@RequiredArgsConstructor
public class MaintenanceController {

    private final MaintenanceService service;

    @GetMapping("/repairs")
    public ApiResponse<List<Repair>> repairs(@RequestParam(defaultValue = "false") boolean all) {
        return ApiResponse.ok(service.myRepairs(all));
    }

    @GetMapping("/repairs/{id}")
    public ApiResponse<Repair> repair(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/repairs/{id}/updates")
    public ApiResponse<List<RepairUpdate>> updates(@PathVariable String id) {
        return ApiResponse.ok(service.updates(id));
    }

    @PostMapping("/repairs")
    public ApiResponse<Repair> open(@Valid @RequestBody MaintenanceRequests.RepairRequest request) {
        return ApiResponse.ok("Repair opened", service.open(request));
    }

    @PutMapping("/repairs/{id}/assign")
    public ApiResponse<Repair> assign(@PathVariable String id, @Valid @RequestBody MaintenanceRequests.AssignRequest request) {
        return ApiResponse.ok("Assigned", service.assign(id, request));
    }

    @PostMapping("/repairs/{id}/updates")
    public ApiResponse<Repair> addUpdate(@PathVariable String id, @Valid @RequestBody MaintenanceRequests.UpdateRequest request) {
        return ApiResponse.ok("Update added", service.addUpdate(id, request));
    }

    @PostMapping("/repairs/{id}/done")
    public ApiResponse<Repair> done(@PathVariable String id, @RequestBody(required = false) MaintenanceRequests.DoneRequest request) {
        return ApiResponse.ok("Repair done", service.done(id, request));
    }

    @PostMapping("/repairs/{id}/out-of-order")
    public ApiResponse<Repair> outOfOrder(@PathVariable String id, @Valid @RequestBody MaintenanceRequests.OutOfOrderRequest request) {
        return ApiResponse.ok("Room out of order", service.takeRoomOutOfOrder(id, request));
    }

    @PostMapping("/repairs/{id}/back-in-service")
    public ApiResponse<Repair> backInService(@PathVariable String id) {
        return ApiResponse.ok("Room back in service", service.putRoomBack(id));
    }

    @PostMapping("/repairs/{id}/parts")
    public ApiResponse<PartRequest> askForParts(@PathVariable String id, @Valid @RequestBody MaintenanceRequests.PartsRequest request) {
        return ApiResponse.ok("Parts requested", service.askForParts(id, request));
    }

    @GetMapping("/part-requests")
    public ApiResponse<List<PartRequest>> partRequests() {
        return ApiResponse.ok(service.partRequests());
    }

    @GetMapping("/scheduled-jobs")
    public ApiResponse<List<ScheduledJob>> scheduledJobs() {
        return ApiResponse.ok(service.scheduledJobs());
    }

    @PostMapping("/scheduled-jobs")
    public ApiResponse<ScheduledJob> addJob(@Valid @RequestBody MaintenanceRequests.ScheduledJobRequest request) {
        return ApiResponse.ok("Repeating job added", service.saveJob(null, request));
    }

    @PutMapping("/scheduled-jobs/{id}")
    public ApiResponse<ScheduledJob> updateJob(@PathVariable String id, @Valid @RequestBody MaintenanceRequests.ScheduledJobRequest request) {
        return ApiResponse.ok("Saved", service.saveJob(id, request));
    }

    @PostMapping("/scheduled-jobs/{id}/done")
    public ApiResponse<ScheduledJob> jobDone(@PathVariable String id, @RequestBody(required = false) MaintenanceRequests.JobDoneRequest request) {
        return ApiResponse.ok("Job done", service.jobDone(id, request));
    }

    @GetMapping("/scheduled-jobs/{id}/runs")
    public ApiResponse<List<ScheduledJobRun>> pastRuns(@PathVariable String id) {
        return ApiResponse.ok(service.pastRuns(id));
    }

    @PutMapping("/scheduled-jobs/{id}/pause")
    public ApiResponse<ScheduledJob> pause(@PathVariable String id, @RequestParam(defaultValue = "true") boolean paused) {
        return ApiResponse.ok(paused ? "Paused" : "Resumed", service.pauseJob(id, paused));
    }

    @DeleteMapping("/scheduled-jobs/{id}")
    public ApiResponse<Void> deleteJob(@PathVariable String id) {
        service.deleteJob(id);
        return ApiResponse.ok("Deleted", null);
    }

    @GetMapping("/spare-parts")
    public ApiResponse<List<SparePart>> spareParts() {
        return ApiResponse.ok(service.spareParts());
    }

    @PostMapping("/spare-parts")
    public ApiResponse<SparePart> addPart(@Valid @RequestBody MaintenanceRequests.SparePartRequest request) {
        return ApiResponse.ok("Part added", service.savePart(null, request));
    }

    @PutMapping("/spare-parts/{id}")
    public ApiResponse<SparePart> updatePart(@PathVariable String id, @Valid @RequestBody MaintenanceRequests.SparePartRequest request) {
        return ApiResponse.ok("Saved", service.savePart(id, request));
    }

    @PostMapping("/spare-parts/{id}/use")
    public ApiResponse<SparePart> usePart(@PathVariable String id, @RequestParam int quantity) {
        return ApiResponse.ok("Part used", service.usePart(id, quantity));
    }
}

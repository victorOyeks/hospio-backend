package com.hospio.housekeeping.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.housekeeping.dto.TaskRequests;
import com.hospio.housekeeping.entity.CleaningTask;
import com.hospio.housekeeping.entity.LinenRequest;
import com.hospio.housekeeping.entity.LostFoundItem;
import com.hospio.housekeeping.entity.MinibarRestock;
import com.hospio.housekeeping.entity.ProblemReport;
import com.hospio.housekeeping.service.HousekeepingService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Housekeeping 1.1-3.5. */
@RestController
@RequiredArgsConstructor
public class HousekeepingController {

    private final HousekeepingService service;

    @GetMapping("/tasks")
    public ApiResponse<List<CleaningTask>> myRooms(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(service.myRooms(date));
    }

    /** Cleanliness of every room for the room board and the manager dashboard. */
    @GetMapping("/room-status")
    public ApiResponse<List<com.hospio.housekeeping.entity.RoomStatus>> roomStatus() {
        return ApiResponse.ok(service.roomBoard(com.hospio.common.tenancy.TenantContext.requireHotel()));
    }

    /** Front desk "mark ready" (CLEAN or INSPECTED) and "send to housekeeping" (DIRTY). */
    @PostMapping("/room-status/{roomId}")
    @PreAuthorize("@access.manager() or @access.atLeast('FRONT_OFFICE','EDIT') or @access.atLeast('HOUSEKEEPING','EDIT')")
    public ApiResponse<com.hospio.housekeeping.entity.RoomStatus> setRoomStatus(@PathVariable String roomId,
                                                                                @Valid @RequestBody TaskRequests.RoomStatusRequest request) {
        return ApiResponse.ok("Room updated", service.setStatus(roomId, request));
    }

    @PostMapping("/tasks/{id}/rush")
    @PreAuthorize("@access.manager() or @access.atLeast('FRONT_OFFICE','EDIT')")
    public ApiResponse<CleaningTask> rush(@PathVariable String id) {
        return ApiResponse.ok("Marked rush", service.rush(id));
    }

    @GetMapping("/tasks/to-inspect")
    public ApiResponse<List<CleaningTask>> toInspect() {
        return ApiResponse.ok(service.toInspect());
    }

    @PostMapping("/tasks")
    public ApiResponse<CleaningTask> assign(@Valid @RequestBody TaskRequests.AssignRequest request) {
        return ApiResponse.ok("Room assigned", service.assign(request));
    }

    @PutMapping("/tasks/{id}/assign")
    public ApiResponse<CleaningTask> reassign(@PathVariable String id, @Valid @RequestBody TaskRequests.ReassignRequest request) {
        return ApiResponse.ok("Reassigned", service.reassign(id, request));
    }

    @PostMapping("/tasks/{id}/start")
    public ApiResponse<CleaningTask> start(@PathVariable String id) {
        return ApiResponse.ok("Started", service.start(id));
    }

    @PutMapping("/tasks/{id}/checklist")
    public ApiResponse<CleaningTask> checklist(@PathVariable String id, @RequestBody TaskRequests.ChecklistRequest request) {
        return ApiResponse.ok("Saved", service.saveChecklist(id, request));
    }

    @PostMapping("/tasks/{id}/done")
    public ApiResponse<CleaningTask> done(@PathVariable String id) {
        return ApiResponse.ok("Room marked clean", service.done(id));
    }

    @PostMapping("/tasks/{id}/skip")
    public ApiResponse<CleaningTask> skip(@PathVariable String id, @Valid @RequestBody TaskRequests.SkipRequest request) {
        return ApiResponse.ok("Skipped", service.skip(id, request));
    }

    @PostMapping("/tasks/{id}/inspect")
    public ApiResponse<CleaningTask> inspect(@PathVariable String id, @Valid @RequestBody TaskRequests.InspectRequest request) {
        return ApiResponse.ok(request.passed() ? "Inspected" : "Sent back", service.inspect(id, request));
    }

    @PostMapping("/problems")
    public ApiResponse<ProblemReport> reportProblem(@Valid @RequestBody TaskRequests.ProblemRequest request) {
        return ApiResponse.ok("Sent to maintenance", service.reportProblem(request));
    }

    @PostMapping("/linen-requests")
    public ApiResponse<LinenRequest> askForLinen(@Valid @RequestBody TaskRequests.LinenRequestDto request) {
        return ApiResponse.ok("Linen request sent", service.askForLinen(request));
    }

    @GetMapping("/linen-requests")
    public ApiResponse<List<LinenRequest>> openLinenRequests() {
        return ApiResponse.ok(service.openLinenRequests());
    }

    @PostMapping("/linen-requests/{id}/sent")
    public ApiResponse<LinenRequest> linenSent(@PathVariable String id) {
        return ApiResponse.ok("Marked sent", service.linenSent(id));
    }

    @PostMapping("/minibar")
    public ApiResponse<MinibarRestock> minibar(@Valid @RequestBody TaskRequests.MinibarRequest request) {
        return ApiResponse.ok("Minibar charged to the room", service.minibar(request));
    }

    @GetMapping("/lost-found")
    public ApiResponse<List<LostFoundItem>> lostFound() {
        return ApiResponse.ok(service.lostAndFound());
    }

    @PostMapping("/lost-found")
    public ApiResponse<LostFoundItem> logFound(@Valid @RequestBody TaskRequests.LostFoundRequest request) {
        return ApiResponse.ok("Item logged", service.logFound(request));
    }

    @PostMapping("/lost-found/{id}/return")
    public ApiResponse<LostFoundItem> handBack(@PathVariable String id, @Valid @RequestBody TaskRequests.ReturnItemRequest request) {
        return ApiResponse.ok("Item handed back", service.handBack(id, request));
    }
}

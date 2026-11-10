package com.hospio.housekeeping.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.housekeeping.dto.TaskRequests;
import com.hospio.housekeeping.entity.CleaningTask;
import com.hospio.housekeeping.entity.LinenRequest;
import com.hospio.housekeeping.entity.LostFoundItem;
import com.hospio.housekeeping.entity.MinibarRestock;
import com.hospio.housekeeping.entity.ProblemReport;
import com.hospio.housekeeping.service.HousekeepingService;
import com.hospio.housekeeping.service.LostFoundService;
import com.hospio.housekeeping.dto.LostFoundView;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.security.AccessChecker;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
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
    private final com.hospio.housekeeping.service.HousekeepingLists lists;
    private final LostFoundService lostFoundService;
    private final AccessChecker access;

    private static final String SUPERVISOR = "Only a housekeeping supervisor can do this";

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
        guard(access.manager() || access.atLeast("HOUSEKEEPING", "FULL"), SUPERVISOR);
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

    @PutMapping("/tasks/{id}/note")
    public ApiResponse<CleaningTask> note(@PathVariable String id, @Valid @RequestBody TaskRequests.NoteRequest request) {
        return ApiResponse.ok("Note saved", service.note(id, request));
    }

    @PostMapping("/tasks/{id}/skip")
    public ApiResponse<CleaningTask> skip(@PathVariable String id, @Valid @RequestBody TaskRequests.SkipRequest request) {
        return ApiResponse.ok("Skipped", service.skip(id, request));
    }

    @PostMapping("/tasks/{id}/inspect")
    public ApiResponse<CleaningTask> inspect(@PathVariable String id, @Valid @RequestBody TaskRequests.InspectRequest request) {
        guard(access.manager() || access.atLeast("HOUSEKEEPING", "FULL"), SUPERVISOR);
        return ApiResponse.ok(request.passed() ? "Inspected" : "Sent back", service.inspect(id, request));
    }

    @PostMapping("/problems")
    public ApiResponse<ProblemReport> reportProblem(@Valid @RequestBody TaskRequests.ProblemRequest request) {
        ProblemReport r = service.reportProblem(request);
        return ApiResponse.ok(r.isOutOfOrder() ? "Sent to maintenance · room out of order · front office told" : "Sent to maintenance", r);
    }

    @PostMapping("/linen-requests")
    public ApiResponse<LinenRequest> askForLinen(@Valid @RequestBody TaskRequests.LinenRequestDto request) {
        guard(access.manager() || access.atLeast("HOUSEKEEPING", "EDIT"), "Only housekeeping or a manager can ask laundry for linen");
        return ApiResponse.ok("Linen request sent", service.askForLinen(request));
    }

    /** Laundry's list of linen asked for by housekeeping: open (default) or {@code status=SENT}. */
    @GetMapping("/linen-requests")
    public ApiResponse<List<LinenRequest>> linenRequests(@RequestParam(required = false) String status) {
        guard(access.managerView() || access.has("LAUNDRY") || access.has("HOUSEKEEPING"), "Only laundry, housekeeping and managers can see linen requests");
        return ApiResponse.ok(service.linenRequests(status));
    }

    @PostMapping("/linen-requests/{id}/sent")
    public ApiResponse<LinenRequest> linenSent(@PathVariable String id) {
        guard(access.manager() || access.atLeast("LAUNDRY", "EDIT"), "Only laundry or a manager can mark linen sent");
        return ApiResponse.ok("Marked sent · housekeeping told", service.linenSent(id));
    }

    @PostMapping("/minibar")
    public ApiResponse<MinibarRestock> minibar(@Valid @RequestBody TaskRequests.MinibarRequest request) {
        return ApiResponse.ok("Minibar charged to the room", service.minibar(request));
    }

    /**
     * Lost & found, a page at a time (docs/pagination.md): {@code status} (FOUND, RETURNED, DISPOSED), {@code q} (item, details, where,
     * room, who found it, who it went back to), {@code from}/{@code to} on the day found. Counts FOUND, RETURNED, DISPOSED and all.
     * Without page or size: the plain list, as before (scripts).
     */
    @GetMapping("/lost-found")
    public ApiResponse<?> lostFound(@RequestParam(required = false) String status, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(required = false) String q, @RequestParam(required = false) String sort,
                                    @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        guard(seeLostFound(), "Only housekeeping, the front office and managers can see lost & found");
        if (!com.hospio.common.paging.Paging.requested(page, size)) {
            return ApiResponse.ok(service.lostAndFound());
        }
        return ApiResponse.ok(lists.lostAndFound(new com.hospio.housekeeping.service.HousekeepingLists.Query(q, status, from, to, sort, page, size)));
    }

    @GetMapping("/lost-found/{id}")
    public ApiResponse<LostFoundView> lostFoundItem(@PathVariable String id) {
        guard(seeLostFound(), "Only housekeeping, the front office and managers can see lost & found");
        return ApiResponse.ok(lostFoundService.get(id));
    }

    @PostMapping("/lost-found")
    public ApiResponse<LostFoundView> logFound(@RequestBody TaskRequests.LostFoundRequest request) {
        guard(handleLostFound(), "Only housekeeping, the front office or a manager can log a found item");
        LostFoundView v = lostFoundService.log(request);
        return ApiResponse.ok(v.frontOfficeToldAt() != null ? "Item logged · front office told" : "Item logged", v);
    }

    @PostMapping("/lost-found/{id}/tell-front-office")
    public ApiResponse<LostFoundView> tellFrontOffice(@PathVariable String id) {
        guard(handleLostFound(), "Only housekeeping, the front office or a manager can tell the front office");
        return ApiResponse.ok("Front office told", lostFoundService.tellFrontOffice(id));
    }

    @PostMapping(value = "/lost-found/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<LostFoundView> lostFoundPhoto(@PathVariable String id, @RequestPart(name = "file", required = false) MultipartFile file) {
        guard(handleLostFound(), "Only housekeeping, the front office or a manager can add a photo");
        return ApiResponse.ok("Photo added", lostFoundService.photo(id, file));
    }

    @GetMapping("/lost-found/{id}/photo")
    public ResponseEntity<byte[]> lostFoundPhoto(@PathVariable String id) {
        guard(seeLostFound(), "Only housekeeping, the front office and managers can see lost & found");
        LostFoundService.Photo p = lostFoundService.photo(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(p.contentType())).cacheControl(CacheControl.noStore()).body(p.bytes());
    }

    @PostMapping("/lost-found/{id}/return")
    public ApiResponse<LostFoundView> handBack(@PathVariable String id, @RequestBody TaskRequests.ReturnItemRequest request) {
        guard(handleLostFound(), "Only housekeeping, the front office or a manager can return an item");
        LostFoundView v = lostFoundService.returnToOwner(id, request);
        return ApiResponse.ok("Returned to " + v.returnedTo(), v);
    }

    @PostMapping("/lost-found/{id}/dispose")
    public ApiResponse<LostFoundView> dispose(@PathVariable String id, @RequestBody(required = false) TaskRequests.DisposeItemRequest request) {
        guard(access.manager() || access.atLeast("HOUSEKEEPING", "FULL"), "Only a supervisor or manager can dispose of an item");
        return ApiResponse.ok("Disposed of", lostFoundService.dispose(id, request));
    }

    private boolean seeLostFound() {
        return access.managerView() || access.atLeast("HOUSEKEEPING", "VIEW") || access.atLeast("FRONT_OFFICE", "VIEW");
    }

    private boolean handleLostFound() {
        return access.manager() || access.atLeast("HOUSEKEEPING", "EDIT") || access.atLeast("FRONT_OFFICE", "EDIT");
    }

    /** A refusal the screen shows as it is (a bare @PreAuthorize would only say "You don't have access to do that"). */
    private static void guard(boolean allowed, String refusal) {
        if (!allowed) {
            throw new ForbiddenException(refusal);
        }
    }
}

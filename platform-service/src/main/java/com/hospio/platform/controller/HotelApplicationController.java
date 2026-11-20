package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.response.PageWithCounts;
import com.hospio.platform.dto.HotelApplicationDtos.ApplicationView;
import com.hospio.platform.dto.HotelApplicationDtos.ApplyRequest;
import com.hospio.platform.dto.HotelApplicationDtos.AssignRequest;
import com.hospio.platform.dto.HotelApplicationDtos.LinkRequest;
import com.hospio.platform.dto.HotelApplicationDtos.MeetingRequest;
import com.hospio.platform.dto.HotelApplicationDtos.NoteRequest;
import com.hospio.platform.dto.HotelApplicationDtos.Permissions;
import com.hospio.platform.dto.HotelApplicationDtos.Received;
import com.hospio.platform.dto.HotelApplicationDtos.StatusRequest;
import com.hospio.platform.dto.HotelApplicationDtos.Submission;
import com.hospio.platform.service.HotelApplicationService;
import com.hospio.platform.service.SiteCallers;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Book a demo" (docs/onboarding.md, "Applications"): the landing site's public form, which replaces self sign-up, and the admin
 * portal's Hotel applications. Any platform admin reads; super admins and support change (the partner-application DECIDE rule).
 */
@RestController
@RequiredArgsConstructor
public class HotelApplicationController {

    /** The people who work the requests: the same as those who decide partner applications. */
    static final String UPDATE = PartnerApplicationController.DECIDE;

    private final HotelApplicationService applications;
    private final SiteCallers site;

    @Operation(summary = "Public: book a demo (landing site). 200 with the same message for a new request, a repeat (added to the open "
            + "request as a note) and a filled hidden field; 400 with a message per field; 429 when one address or email sent too many in an hour")
    @PostMapping("/public/hotel-applications")
    public ResponseEntity<ApiResponse<Object>> apply(@RequestBody(required = false) ApplyRequest request, HttpServletRequest http) {
        Submission s = applications.submit(request, site.visitorAddress(http), http.getHeader("User-Agent"));
        if (Submission.INVALID.equals(s.outcome())) {
            String first = s.errors().values().stream().findFirst().orElse("Check the form");
            return ResponseEntity.badRequest().body(ApiResponse.error(first, s.errors()));
        }
        if (Submission.LIMITED.equals(s.outcome())) {
            return ResponseEntity.status(429).header("Retry-After", "3600")
                    .body(ApiResponse.error(HotelApplicationService.LIMITED_MESSAGE + applications.supportEmail() + "."));
        }
        return ResponseEntity.ok(ApiResponse.ok(HotelApplicationService.RECEIVED_MESSAGE, new Received(true, HotelApplicationService.RECEIVED_MESSAGE)));
    }

    @Operation(summary = "Hotel applications, newest first, paged (page from 0, size 1 to 100, 25 by default), by status (OPEN = not onboarded "
            + "or declined, NEW, CONTACTED, MEETING_BOOKED, APPROVED, ONBOARDED, DECLINED), assignee, monthly sales band (UNDER_1M, FROM_1M_TO_5M, "
            + "FROM_5M_TO_20M, ABOVE_20M, PREFER_NOT, or NOT_ASKED for requests sent before the question) and search; sort NEWEST (default) or "
            + "SALES (highest band first, Prefer not to say then Not asked last); counts per status for the search and band, and newTotal for the sidebar badge")
    @GetMapping("/admin/hotel-applications")
    @PreAuthorize("@access.platform()")
    public ApiResponse<PageWithCounts<ApplicationView>> list(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                                             @RequestParam(required = false) String assigneeId,
                                                             @RequestParam(required = false) String monthlySales, @RequestParam(required = false) String sort,
                                                             @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(applications.list(status, q, assigneeId, monthlySales, sort, page, size));
    }

    @Operation(summary = "How many requests are NEW (the sidebar badge)")
    @GetMapping("/admin/hotel-applications/new-count")
    @PreAuthorize("@access.platform()")
    public ApiResponse<Map<String, Long>> newCount() {
        return ApiResponse.ok(Map.of("count", applications.newCount()));
    }

    @Operation(summary = "What the caller may do on Hotel applications")
    @GetMapping("/admin/hotel-applications/permissions")
    @PreAuthorize("@access.platform()")
    public ApiResponse<Permissions> permissions() {
        return ApiResponse.ok(applications.permissions());
    }

    @Operation(summary = "One hotel application with its timeline (notes, status changes)")
    @GetMapping("/admin/hotel-applications/{id}")
    @PreAuthorize("@access.platform()")
    public ApiResponse<ApplicationView> get(@PathVariable String id) {
        return ApiResponse.ok(applications.get(id));
    }

    @Operation(summary = "Change the status (NEW, CONTACTED, MEETING_BOOKED with meetingAt, APPROVED, DECLINED with a reason); an optional note "
            + "goes on the timeline. ONBOARDED comes from linking the business")
    @PutMapping("/admin/hotel-applications/{id}/status")
    @PreAuthorize(UPDATE)
    public ApiResponse<ApplicationView> status(@PathVariable String id, @RequestBody(required = false) StatusRequest request) {
        return ApiResponse.ok("Status changed", applications.changeStatus(id, request));
    }

    @Operation(summary = "Add a note (append-only, with author and time)")
    @PostMapping("/admin/hotel-applications/{id}/notes")
    @PreAuthorize(UPDATE)
    public ApiResponse<ApplicationView> note(@PathVariable String id, @RequestBody(required = false) NoteRequest request) {
        return ApiResponse.ok("Note added", applications.addNote(id, request == null ? null : request.text()));
    }

    @Operation(summary = "Assign to a super admin or support admin; a blank assigneeId unassigns")
    @PutMapping("/admin/hotel-applications/{id}/assignee")
    @PreAuthorize(UPDATE)
    public ApiResponse<ApplicationView> assign(@PathVariable String id, @RequestBody(required = false) AssignRequest request) {
        return ApiResponse.ok("Assignee saved", applications.assign(id, request == null ? null : request.assigneeId()));
    }

    @Operation(summary = "Set, move or clear (null) the meeting date and time")
    @PutMapping("/admin/hotel-applications/{id}/meeting")
    @PreAuthorize(UPDATE)
    public ApiResponse<ApplicationView> meeting(@PathVariable String id, @RequestBody(required = false) MeetingRequest request) {
        return ApiResponse.ok("Meeting saved", applications.meeting(id, request == null ? null : request.meetingAt()));
    }

    @Operation(summary = "Link the business onboarded from this application (back-office wizard); the application becomes ONBOARDED")
    @PostMapping("/admin/hotel-applications/{id}/link")
    @PreAuthorize(UPDATE)
    public ApiResponse<ApplicationView> link(@PathVariable String id, @RequestBody(required = false) LinkRequest request) {
        return ApiResponse.ok("Business linked", applications.link(id, request == null ? null : request.businessId()));
    }
}

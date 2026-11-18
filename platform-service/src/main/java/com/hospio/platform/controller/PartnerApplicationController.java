package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.response.PageWithCounts;
import com.hospio.platform.dto.PartnerApplicationDtos.ApplicationView;
import com.hospio.platform.dto.PartnerApplicationDtos.ApplyRequest;
import com.hospio.platform.dto.PartnerApplicationDtos.ApproveRequest;
import com.hospio.platform.dto.PartnerApplicationDtos.Approved;
import com.hospio.platform.dto.PartnerApplicationDtos.DeclineRequest;
import com.hospio.platform.dto.PartnerApplicationDtos.Received;
import com.hospio.platform.dto.PartnerApplicationDtos.ReviewDefaults;
import com.hospio.platform.dto.PartnerApplicationDtos.Submission;
import com.hospio.platform.service.PartnerApplicationService;
import com.hospio.platform.service.SiteCallers;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** "Apply to be a partner" (docs/plan-marketers.md): the landing site's public form, and its review in Marketers · Applications. */
@RestController
@RequiredArgsConstructor
public class PartnerApplicationController {

    /** The people who invite marketers in the admin portal. */
    static final String DECIDE = "hasAnyRole('SUPER_ADMIN','SUPPORT')";

    private final PartnerApplicationService applications;
    private final SiteCallers site;

    @Operation(summary = "Public: apply to be a referral partner (landing site). 200 with the same message for a new application, a repeat "
            + "and a filled hidden field; 400 with a message per field; 429 when one address or email sent too many in an hour")
    @PostMapping("/public/partner-applications")
    public ResponseEntity<ApiResponse<Object>> apply(@RequestBody(required = false) ApplyRequest request, HttpServletRequest http) {
        Submission s = applications.submit(request, site.visitorAddress(http), http.getHeader("User-Agent"));
        if (Submission.INVALID.equals(s.outcome())) {
            String first = s.errors().values().stream().findFirst().orElse("Check the form");
            return ResponseEntity.badRequest().body(ApiResponse.error(first, s.errors()));
        }
        if (Submission.LIMITED.equals(s.outcome())) {
            return ResponseEntity.status(429).header("Retry-After", "3600")
                    .body(ApiResponse.error(PartnerApplicationService.LIMITED_MESSAGE + applications.supportEmail() + "."));
        }
        return ResponseEntity.ok(ApiResponse.ok(PartnerApplicationService.RECEIVED_MESSAGE, new Received(true, PartnerApplicationService.RECEIVED_MESSAGE)));
    }

    @Operation(summary = "Partner applications, newest first, paged (page from 0, size 1 to 100, 25 by default), by status (PENDING, APPROVED, "
            + "DECLINED) and search; counts per status for the search, and pendingTotal for the tab's badge")
    @GetMapping("/admin/partner-applications")
    @PreAuthorize("@access.platform()")
    public ApiResponse<PageWithCounts<ApplicationView>> list(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                                             @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(applications.list(status, q, page, size));
    }

    @Operation(summary = "The approve form's defaults (share and months from Settings · Fees) and what the caller may do")
    @GetMapping("/admin/partner-applications/defaults")
    @PreAuthorize("@access.platform()")
    public ApiResponse<ReviewDefaults> defaults() {
        return ApiResponse.ok(applications.defaults());
    }

    @Operation(summary = "One partner application")
    @GetMapping("/admin/partner-applications/{id}")
    @PreAuthorize("@access.platform()")
    public ApiResponse<ApplicationView> get(@PathVariable String id) {
        return ApiResponse.ok(applications.get(id));
    }

    @Operation(summary = "Approve: invites the applicant as a marketer (the usual invite, with its email), marks the application APPROVED and "
            + "links the marketer. A share or months other than the settings' needs SUPER_ADMIN or FINANCE_ADMIN")
    @PostMapping("/admin/partner-applications/{id}/approve")
    @PreAuthorize(DECIDE)
    public ApiResponse<Approved> approve(@PathVariable String id, @RequestBody(required = false) ApproveRequest request) {
        return ApiResponse.ok("Application approved; the marketer was invited", applications.approve(id, request));
    }

    @Operation(summary = "Decline with a reason; the applicant is emailed, with the reason only when shareReason is true")
    @PostMapping("/admin/partner-applications/{id}/decline")
    @PreAuthorize(DECIDE)
    public ApiResponse<ApplicationView> decline(@PathVariable String id, @RequestBody(required = false) DeclineRequest request) {
        return ApiResponse.ok("Application declined", applications.decline(id, request));
    }
}

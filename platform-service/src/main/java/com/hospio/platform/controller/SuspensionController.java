package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.platform.service.SuspensionService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Real suspension of a business (docs/suspension.md): who is 30+ days overdue, the 7 days' notice, suspend and reactivate. Reading is
 * for every platform role; the actions are for super and finance admins (the finer rules are in {@link SuspensionService}).
 */
@RestController
@RequiredArgsConstructor
public class SuspensionController {

    private final SuspensionService suspensions;

    @Operation(summary = "Admin: businesses with an invoice 30+ days overdue that are not suspended (Suspension candidates)")
    @GetMapping("/admin/suspension/candidates")
    @PreAuthorize("@access.platform()")
    public ApiResponse<List<SuspensionService.Candidate>> candidates() {
        return ApiResponse.ok(suspensions.candidates());
    }

    @Operation(summary = "Admin: a business's suspension state, overdue invoices and last notice")
    @GetMapping("/admin/businesses/{businessId}/suspension")
    @PreAuthorize("@access.platform()")
    public ApiResponse<SuspensionService.State> state(@PathVariable String businessId) {
        return ApiResponse.ok(suspensions.state(businessId));
    }

    @Operation(summary = "Admin: send the 7 days' suspension notice (email to the owner, bell to owners and managers) and record its date")
    @PostMapping("/admin/businesses/{businessId}/suspension/notice")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<SuspensionService.State> notice(@PathVariable String businessId, @RequestBody(required = false) SuspensionService.NoticeRequest request) {
        return ApiResponse.ok("Notice sent", suspensions.sendNotice(businessId, request));
    }

    @Operation(summary = "Admin: suspend a business {reasonType: UNPAID_INVOICE|OTHER, note, showNote, noticeSent, noticeSentOn}")
    @PostMapping("/admin/businesses/{businessId}/suspend")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<SuspensionService.State> suspend(@PathVariable String businessId, @RequestBody SuspensionService.SuspendRequest request) {
        return ApiResponse.ok("Business suspended", suspensions.suspend(businessId, request));
    }

    @Operation(summary = "Admin: reactivate a suspended business {note}: back to the stage it had")
    @PostMapping("/admin/businesses/{businessId}/reactivate")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<SuspensionService.State> reactivate(@PathVariable String businessId, @RequestBody SuspensionService.ReactivateRequest request) {
        return ApiResponse.ok("Business reactivated", suspensions.reactivate(businessId, request));
    }
}

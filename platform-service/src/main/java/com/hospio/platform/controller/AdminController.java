package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.Commission;
import com.hospio.platform.entity.FeeRule;
import com.hospio.platform.entity.FeeStatement;
import com.hospio.platform.entity.HealthScore;
import com.hospio.platform.entity.Marketer;
import com.hospio.platform.entity.PlatformChange;
import com.hospio.platform.entity.SupportTicket;
import com.hospio.platform.service.PlatformAdminService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform admin D (fees), E (marketers), G (support), L (edit as H.M.S, health) and the marketer portal F. */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final PlatformAdminService service;

    @GetMapping("/fees/rules")
    @PreAuthorize("@access.platform()")
    public ApiResponse<List<FeeRule>> feeRules(@RequestParam String businessId) {
        return ApiResponse.ok(service.feeRules(businessId));
    }

    @PostMapping("/fees/rules")
    @PreAuthorize("@access.platform()")
    public ApiResponse<FeeRule> saveFeeRule(@Valid @RequestBody PlatformRequests.FeeRuleRequest request) {
        return ApiResponse.ok("Fee rule saved", service.saveFeeRule(request));
    }

    @GetMapping("/fees/statements")
    public ApiResponse<List<FeeStatement>> statements(@RequestParam(required = false) String businessId) {
        return ApiResponse.ok(service.statements(businessId));
    }

    @PostMapping("/fees/statements")
    @PreAuthorize("@access.platform()")
    public ApiResponse<FeeStatement> createStatement(@Valid @RequestBody PlatformRequests.FeeStatementRequest request) {
        return ApiResponse.ok("Statement created", service.createStatement(request));
    }

    @PostMapping("/fees/statements/{id}/payments")
    @PreAuthorize("@access.platform()")
    public ApiResponse<FeeStatement> recordFeePayment(@PathVariable String id, @Valid @RequestBody PlatformRequests.FeePaymentRequest request) {
        return ApiResponse.ok("Payment recorded", service.recordFeePayment(id, request));
    }

    @GetMapping("/marketers")
    @PreAuthorize("@access.platform()")
    public ApiResponse<List<Marketer>> marketers() {
        return ApiResponse.ok(service.marketers());
    }

    @GetMapping("/marketers/mine")
    @PreAuthorize("hasRole('MARKETER')")
    public ApiResponse<List<Marketer>> myReferrals() {
        return ApiResponse.ok(service.myReferrals());
    }

    @PostMapping("/marketers")
    @PreAuthorize("@access.platform()")
    public ApiResponse<Marketer> addMarketer(@Valid @RequestBody PlatformRequests.MarketerRequest request) {
        return ApiResponse.ok("Marketer added", service.addMarketer(request));
    }

    @PutMapping("/marketers/{id}/status")
    @PreAuthorize("@access.platform()")
    public ApiResponse<Marketer> marketerStatus(@PathVariable String id, @RequestParam String status) {
        return ApiResponse.ok("Updated", service.setMarketerStatus(id, status));
    }

    @GetMapping("/commissions")
    public ApiResponse<List<Commission>> commissions(@RequestParam(required = false) String marketerId) {
        return ApiResponse.ok(service.commissions(marketerId));
    }

    @PostMapping("/commissions/{id}/pay")
    @PreAuthorize("@access.platform()")
    public ApiResponse<Commission> payCommission(@PathVariable String id) {
        return ApiResponse.ok("Paid", service.payCommission(id));
    }

    @PostMapping("/support/tickets/{id}/resolve")
    @PreAuthorize("@access.platform()")
    public ApiResponse<SupportTicket> resolveTicket(@PathVariable String id, @Valid @RequestBody PlatformRequests.ResolveTicketRequest request) {
        return ApiResponse.ok("Resolved", service.resolveTicket(id, request));
    }

    @PostMapping("/health/{businessId}")
    @PreAuthorize("@access.platform()")
    public ApiResponse<HealthScore> health(@PathVariable String businessId) {
        return ApiResponse.ok(service.computeHealth(businessId));
    }

    /** L1-L3 · Edit as H.M.S: the change, the reason, and what it looked like before. The owner can undo for 7 days. */
    @PostMapping("/changes")
    @PreAuthorize("@access.platform()")
    public ApiResponse<PlatformChange> recordChange(@Valid @RequestBody PlatformRequests.PlatformChangeRequest request) {
        return ApiResponse.ok("Change recorded; the owner has been told", service.recordChange(request));
    }
}

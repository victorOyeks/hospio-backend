package com.hospio.platform.controller;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.Marketer;
import com.hospio.platform.entity.MarketerProfile;
import com.hospio.platform.repository.MarketerProfileRepository;
import com.hospio.platform.repository.MarketerRepository;
import com.hospio.platform.service.CommissionService;
import com.hospio.platform.service.MarketerService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
 * Marketer commission (docs/plan-marketers.md): monthly statements for finance, the marketer's own statements, queries and agreement,
 * who referred a business, refunds of a collected daily fee (clawback) and the public referral-code check for the sign-up page.
 */
@RestController
@RequiredArgsConstructor
public class MarketerStatementController {

    private final CommissionService commission;
    private final MarketerService marketers;
    private final MarketerRepository agreements;
    private final MarketerProfileRepository profiles;
    private final TenantClient tenant;

    @Value("${hospio.qa.fee-simulation:false}")
    private boolean qa;

    // ---- finance ---------------------------------------------------------------------------------------

    @Operation(summary = "Marketer statements, newest month first, by month (yyyy-MM), status and marketer; counts per status and payable totals")
    @GetMapping("/admin/marketer-statements")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<CommissionService.StatementList> list(@RequestParam(required = false) String month, @RequestParam(required = false) String status,
                                                            @RequestParam(required = false) String profileId) {
        return ApiResponse.ok(commission.list(month, status, profileId));
    }

    @Operation(summary = "Marketer statements as CSV (finance export)")
    @GetMapping(value = "/admin/marketer-statements/export", produces = "text/csv")
    @PreAuthorize(AdminFeesController.MONEY)
    public ResponseEntity<String> export(@RequestParam(required = false) String month, @RequestParam(required = false) String status) {
        String name = "marketer-statements" + (month == null ? "" : "-" + month) + ".csv";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8")).body(commission.csv(month, status));
    }

    @Operation(summary = "One statement: totals, lines per business, clawbacks and statements carried into it")
    @GetMapping("/admin/marketer-statements/{id}")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<CommissionService.StatementDetail> get(@PathVariable String id) {
        return ApiResponse.ok(commission.detail(id));
    }

    @Operation(summary = "Make the statements for a month now (the job does the month before on the 5th). The current month only on staging (QA)")
    @PostMapping("/admin/marketer-statements/run")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<CommissionService.StatementRun> run(@RequestParam(required = false) String month) {
        YearMonth now = YearMonth.now(ZoneId.of("Africa/Lagos"));
        YearMonth m = month == null ? now.minusMonths(1) : YearMonth.parse(month);
        if (m.isAfter(now) || m.equals(now) && !qa) {
            throw new BadRequestException("A month's statements are made once it has ended (the job runs on the 5th)");
        }
        return ApiResponse.ok(commission.makeStatements(m));
    }

    @Operation(summary = "Approve a draft or queried statement (answer the query); the marketer's bank details must be checked")
    @PostMapping("/admin/marketer-statements/{id}/approve")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<CommissionService.StatementDetail> approve(@PathVariable String id, @Valid @RequestBody(required = false) PlatformRequests.StatementApproveRequest request) {
        return ApiResponse.ok("Approved", commission.approve(id, request == null ? null : request.answer()));
    }

    @Operation(summary = "Record the bank transfer that paid an approved statement: reference, date, amount (the whole payable); emails the marketer")
    @PostMapping("/admin/marketer-statements/{id}/payment")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<CommissionService.StatementDetail> pay(@PathVariable String id, @Valid @RequestBody PlatformRequests.StatementPaymentRequest request) {
        return ApiResponse.ok("Payment recorded", commission.recordPayment(id, request.reference(), request.paidOn(), request.amount()));
    }

    @Operation(summary = "Mark a marketer's current bank details checked (needed before their statements can be approved)")
    @PostMapping("/admin/marketers/{id}/bank-checked")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<MarketerProfile> bankChecked(@PathVariable String id) {
        return ApiResponse.ok("Bank details checked", marketers.markBankChecked(id));
    }

    @Operation(summary = "Record that Hospio360 gave back part of a collected daily fee (reversal or refund); the marketer's commission on it is clawed back")
    @PostMapping("/admin/fees/daily/{id}/refund")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<CommissionService.RefundResult> refund(@PathVariable String id, @Valid @RequestBody PlatformRequests.FeeRefundRequest request) {
        return ApiResponse.ok("Refund recorded", commission.refund(id, request.amount(), request.reason()));
    }

    // ---- who referred a business -----------------------------------------------------------------------

    public record BusinessMarketer(String businessId, String profileId, String fullName, String referralCode, String status, List<Marketer> agreements) {
    }

    @Operation(summary = "Who referred the business (name, code) and its marketer agreements")
    @GetMapping("/admin/businesses/{businessId}/marketer")
    @PreAuthorize("@access.platform()")
    public ApiResponse<BusinessMarketer> businessMarketer(@PathVariable String businessId) {
        String profileId = tenant.business(businessId).map(TenantClient.Business::marketerId).orElse(null);
        return ApiResponse.ok(TenantContext.runWith(Tenant.system("marketers"), () -> {
            MarketerProfile p = profileId == null ? null : profiles.findById(profileId).orElse(null);
            return new BusinessMarketer(businessId, profileId, p == null ? null : p.getFullName(), p == null ? null : p.getReferralCode(),
                    p == null ? null : p.getStatus(), agreements.findByBusinessId(businessId));
        }));
    }

    @Operation(summary = "Set or change who referred the business (reason required; audit-logged). Ends the old marketer's agreement; opens the "
            + "new one today if the business is live")
    @PutMapping("/admin/businesses/{businessId}/marketer")
    @PreAuthorize("@access.platform()")
    public ApiResponse<MarketerService.MarketerChange> setBusinessMarketer(@PathVariable String businessId, @Valid @RequestBody PlatformRequests.BusinessMarketerRequest request) {
        return ApiResponse.ok("Referred by updated", marketers.changeMarketer(businessId, request.profileId(), request.reason()));
    }

    // ---- the marketer ----------------------------------------------------------------------------------

    @Operation(summary = "Accept the Referral Partner Agreement (current version); needed before sharing the referral link")
    @PostMapping("/marketer/agreement")
    @PreAuthorize("hasRole('MARKETER')")
    public ApiResponse<MarketerProfile> accept(@Valid @RequestBody PlatformRequests.AgreementRequest request) {
        return ApiResponse.ok("Agreement accepted", marketers.acceptAgreement(request.version()));
    }

    @Operation(summary = "My statements, newest first, and the lines waiting for the next one")
    @GetMapping("/marketer/statements")
    @PreAuthorize("hasRole('MARKETER')")
    public ApiResponse<MarketerService.MyStatements> myStatements() {
        return ApiResponse.ok(marketers.myStatements());
    }

    @Operation(summary = "One of my statements, with lines per business")
    @GetMapping("/marketer/statements/{id}")
    @PreAuthorize("hasRole('MARKETER')")
    public ApiResponse<CommissionService.StatementDetail> myStatement(@PathVariable String id) {
        return ApiResponse.ok(marketers.myStatementDetail(id));
    }

    @Operation(summary = "Query one of my statements (within 7 days of it being made)")
    @PostMapping("/marketer/statements/{id}/query")
    @PreAuthorize("hasRole('MARKETER')")
    public ApiResponse<CommissionService.StatementDetail> query(@PathVariable String id, @Valid @RequestBody PlatformRequests.StatementQueryRequest request) {
        return ApiResponse.ok("Query sent to Hospio360", marketers.queryStatement(id, request.note()));
    }

    // ---- public: the sign-up page ------------------------------------------------------------------------

    public record ReferralCodeCheck(String code, boolean valid, String referrerFirstName, String message) {
    }

    @Operation(summary = "Public: is this referral code one that can refer? (sign-up page, ?ref=)")
    @GetMapping("/public/referral-codes/{code}")
    public ApiResponse<ReferralCodeCheck> check(@PathVariable String code) {
        String c = code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
        if (c.length() > 20) {
            return ApiResponse.ok(new ReferralCodeCheck(c, false, null, "That referral code isn't recognised"));
        }
        return ApiResponse.ok(marketers.referralCode(c).map(r -> r.canRefer()
                ? new ReferralCodeCheck(r.code(), true, r.fullName() == null ? null : r.fullName().trim().split("\\s+")[0], null)
                : new ReferralCodeCheck(r.code(), false, null, "That referral code isn't active"))
                .orElse(new ReferralCodeCheck(c, false, null, "That referral code isn't recognised")));
    }
}

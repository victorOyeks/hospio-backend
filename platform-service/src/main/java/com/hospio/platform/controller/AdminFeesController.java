package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.response.PagedResponse;
import com.hospio.platform.dto.AdminResponses.DeliveriesResponse;
import com.hospio.platform.dto.AdminResponses.FeeStatementResponse;
import com.hospio.platform.dto.AdminResponses.GenerateResult;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.FeeRule;
import com.hospio.platform.service.FeeService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fees & billing (D). Money routes are SUPER_ADMIN or FINANCE_ADMIN: {@code @access.has(role)} lets every platform
 * user through, so these use the roles themselves.
 */
@RestController
@RequestMapping("/admin/fees")
@RequiredArgsConstructor
@PreAuthorize(AdminFeesController.MONEY)
public class AdminFeesController {

    static final String MONEY = "hasAnyRole('SUPER_ADMIN','FINANCE_ADMIN')";

    private final FeeService fees;

    @Operation(summary = "Fee rules of a business, newest first")
    @GetMapping("/rules")
    public ApiResponse<List<FeeRule>> rules(@RequestParam String businessId) {
        return ApiResponse.ok(fees.rules(businessId));
    }

    @Operation(summary = "Add a fee rule; the business's feeModel/feeValue in tenant-service follow it")
    @PostMapping("/rules")
    public ApiResponse<FeeRule> saveRule(@Valid @RequestBody PlatformRequests.FeeRuleRequest request) {
        return ApiResponse.ok("Fee rule saved", fees.saveRule(request));
    }

    @Operation(summary = "Fee statements, paged, filtered by business, status (DUE, PART_PAID, PAID, OVERDUE) and month (yyyy-MM), with their payments")
    @GetMapping("/statements")
    public ApiResponse<PagedResponse<FeeStatementResponse>> statements(@RequestParam(required = false) String businessId,
                                                                       @RequestParam(required = false) String status,
                                                                       @RequestParam(required = false) String month,
                                                                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(fees.statements(businessId, status, month, page, size));
    }

    @Operation(summary = "One statement with its lines and payments")
    @GetMapping("/statements/{id}")
    public ApiResponse<FeeStatementResponse> statement(@PathVariable String id) {
        return ApiResponse.ok(fees.statement(id));
    }

    @Operation(summary = "Generate the month's statements for every LIVE/AT_RISK business (or the one named) from its fee model; sends each to the owner")
    @PostMapping("/statements/generate")
    public ApiResponse<GenerateResult> generate(@Valid @RequestBody PlatformRequests.GenerateStatementsRequest request) {
        GenerateResult r = fees.generate(request);
        return ApiResponse.ok(r.created() + " created, " + r.skipped() + " skipped", r);
    }

    @Operation(summary = "A statement for one business with a base you give (manual)")
    @PostMapping("/statements")
    public ApiResponse<FeeStatementResponse> createManual(@Valid @RequestBody PlatformRequests.FeeStatementRequest request) {
        return ApiResponse.ok("Statement created", fees.createManual(request));
    }

    @Operation(summary = "Record money received against a statement; its status is recomputed")
    @PostMapping("/statements/{id}/payments")
    public ApiResponse<FeeStatementResponse> recordPayment(@PathVariable String id, @Valid @RequestBody PlatformRequests.FeePaymentRequest request) {
        return ApiResponse.ok("Payment recorded", fees.recordPayment(id, request));
    }

    @Operation(summary = "Send (again) the FEE_STATEMENT email to the owner; echoed under deliveries when DELIVERIES_ECHO is on")
    @PostMapping("/statements/{id}/send")
    public ApiResponse<DeliveriesResponse> send(@PathVariable String id) {
        return ApiResponse.ok("Statement sent", new DeliveriesResponse(fees.send(id)));
    }
}

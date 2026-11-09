package com.hospio.payment.controller;

import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.response.PagedResponse;
import com.hospio.payment.dto.AdminPaymentDtos.BatchDetail;
import com.hospio.payment.dto.AdminPaymentDtos.CreateBatchRequest;
import com.hospio.payment.dto.AdminPaymentDtos.FailSettlementRequest;
import com.hospio.payment.dto.AdminPaymentDtos.MarkPaidRequest;
import com.hospio.payment.dto.AdminPaymentDtos.RetryResult;
import com.hospio.payment.dto.AdminPaymentDtos.RunResult;
import com.hospio.payment.dto.AdminPaymentDtos.RunSettlementsRequest;
import com.hospio.payment.dto.AdminPaymentDtos.Summary;
import com.hospio.payment.dto.PaymentResponse;
import com.hospio.payment.entity.FailedPayout;
import com.hospio.payment.entity.PayoutBatch;
import com.hospio.payment.entity.Settlement;
import com.hospio.payment.service.AdminPaymentQueryService;
import com.hospio.payment.service.PayoutService;
import com.hospio.payment.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform admin · Payments: settlements, payout batches, failed payouts (SUPER_ADMIN or FINANCE_ADMIN), and
 * the read-only cross-business payment list and summary (every platform user). A platform token passes the
 * row-level policies, so these read across businesses.
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminPaymentController {

    static final String MONEY = "hasAnyRole('SUPER_ADMIN','FINANCE_ADMIN')";

    private final SettlementService settlements;
    private final PayoutService payouts;
    private final AdminPaymentQueryService queries;

    @Operation(summary = "Settlements, paged, by business, status (PENDING, SETTLED, FAILED) and period date range")
    @GetMapping("/settlements")
    @PreAuthorize(MONEY)
    public ApiResponse<PagedResponse<Settlement>> settlements(@RequestParam(required = false) String businessId, @RequestParam(required = false) Settlement.Status status,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(settlements.search(businessId, status, from, to, page, size));
    }

    @Operation(summary = "Build the day's settlements now (default: yesterday); one per business and provider, existing ones kept")
    @PostMapping("/settlements/run")
    @PreAuthorize(MONEY)
    public ApiResponse<RunResult> run(@RequestBody(required = false) RunSettlementsRequest request,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        RunResult r = settlements.run(request != null && request.date() != null ? request.date() : date);
        return ApiResponse.ok(r.created() + " settlements created for " + r.date(), r);
    }

    @Operation(summary = "Payout batches, newest first")
    @GetMapping("/payouts/batches")
    @PreAuthorize(MONEY)
    public ApiResponse<PagedResponse<PayoutBatch>> batches(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(payouts.batches(page, size));
    }

    @Operation(summary = "One payout batch with its settlements")
    @GetMapping("/payouts/batches/{id}")
    @PreAuthorize(MONEY)
    public ApiResponse<BatchDetail> batch(@PathVariable String id) {
        return ApiResponse.ok(payouts.batch(id));
    }

    @Operation(summary = "Create a batch from the settlements named (default: every PENDING one); they become SETTLED, unpaid until the batch is marked paid")
    @PostMapping("/payouts/batches")
    @PreAuthorize(MONEY)
    public ApiResponse<BatchDetail> createBatch(@RequestBody(required = false) CreateBatchRequest request) {
        BatchDetail d = payouts.create(request);
        return ApiResponse.ok("Batch " + d.batch().getRef() + " created", d);
    }

    @Operation(summary = "The bank paid the batch: PAID with the reference and date; settlements get settledAt")
    @PostMapping("/payouts/batches/{id}/mark-paid")
    @PreAuthorize(MONEY)
    public ApiResponse<BatchDetail> markPaid(@PathVariable String id, @RequestBody(required = false) MarkPaidRequest request) {
        return ApiResponse.ok("Batch marked paid", payouts.markPaid(id, request));
    }

    @Operation(summary = "One settlement of the batch bounced: it fails, a failed payout opens, the batch is PART_FAILED")
    @PostMapping("/payouts/batches/{id}/fail-settlement")
    @PreAuthorize(MONEY)
    public ApiResponse<BatchDetail> failSettlement(@PathVariable String id, @RequestBody FailSettlementRequest request) {
        return ApiResponse.ok("Settlement marked failed", payouts.failSettlement(id, request));
    }

    @Operation(summary = "Open failed payouts, paged")
    @GetMapping("/payouts/failed")
    @PreAuthorize(MONEY)
    public ApiResponse<PagedResponse<FailedPayout>> failed(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(payouts.failedOpen(page, size));
    }

    @Operation(summary = "Retry a failed payout (attempts + 1); resolve=true closes it and settles the settlement now")
    @PostMapping("/payouts/failed/{id}/retry")
    @PreAuthorize(MONEY)
    public ApiResponse<RetryResult> retry(@PathVariable String id, @RequestParam(defaultValue = "false") boolean resolve) {
        return ApiResponse.ok(resolve ? "Payout resolved" : "Retry recorded", payouts.retry(id, resolve));
    }

    @Operation(summary = "Every payment across businesses (view only), paged, by business, hotel, status and date range")
    @GetMapping("/payments")
    @PreAuthorize("@access.platform()")
    public ApiResponse<PagedResponse<PaymentResponse>> payments(@RequestParam(required = false) String businessId, @RequestParam(required = false) String hotelId,
                                                                @RequestParam(required = false) PaymentStatus status,
                                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                                @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(queries.search(businessId, hotelId, status, from, to, page, size));
    }

    @Operation(summary = "A business's approved payments in a period (default this month): gross, count, by provider, by hotel")
    @GetMapping("/summary")
    @PreAuthorize("@access.platform()")
    public ApiResponse<Summary> summary(@RequestParam String businessId,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(queries.summary(businessId, from, to));
    }
}

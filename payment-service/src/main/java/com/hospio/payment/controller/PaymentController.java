package com.hospio.payment.controller;

import com.hospio.common.paging.Paging;
import com.hospio.common.response.ApiResponse;
import com.hospio.payment.dto.DisputeRequest;
import com.hospio.payment.dto.PaymentRequestDto;
import com.hospio.payment.dto.PaymentResponse;
import com.hospio.payment.dto.RefundRequestDto;
import com.hospio.payment.dto.ResolveDisputeRequest;
import com.hospio.payment.entity.Dispute;
import com.hospio.payment.entity.Refund;
import com.hospio.payment.service.PaymentListService;
import com.hospio.payment.service.PaymentService;
import com.hospio.payment.service.ReceiptService;
import com.hospio.payment.service.RefundService;
import jakarta.validation.Valid;
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

/** Taking payment (8.x), My payments & Verify (9.x), and Manager · Payments. */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService payments;
    private final RefundService refunds;
    private final ReceiptService receipts;
    private final PaymentListService lists;
    private final com.hospio.payment.query.TerminalTotalsQuery terminalTotals;

    private static final java.time.ZoneId LAGOS = java.time.ZoneId.of("Africa/Lagos");

    /**
     * Payments the caller may see, newest first. With {@code page} or {@code size}: one page (docs/pagination.md) with tab counts and the
     * money above the list, filtered by {@code q, status (comma separated), method, role, from, to (Lagos days; from defaults to 90 days
     * ago), mine, today} and, for the owner without X-Hotel-Id, {@code hotelId}. Without either: the plain list since {@code from} (90 days ago by default), as before (scripts).
     */
    @GetMapping
    public ApiResponse<?> mine(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                               @RequestParam(required = false) String sort, @RequestParam(required = false) String q,
                               @RequestParam(required = false) String status, @RequestParam(required = false) String method,
                               @RequestParam(required = false) String role,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(required = false) Boolean mine, @RequestParam(required = false) String hotelId,
                               @RequestParam(required = false) Boolean today) {
        if (!Paging.requested(page, size)) {
            return ApiResponse.ok(payments.mine(from));
        }
        return ApiResponse.ok(lists.payments(new PaymentListService.Params(page, size, sort, q, status, method, role, from, to, mine, hotelId, today)));
    }

    /**
     * Approved payments for outlet sales at the active location over the Lagos days {@code from}..{@code to} (today when omitted, up to 93
     * days), per method and POS terminal: the venue report "Payments by method and POS" (docs/plan-venue-type.md phase 2).
     */
    @GetMapping("/by-terminal")
    @PreAuthorize("@access.managerView() or @access.atLeast('FINANCE','VIEW')")
    public ApiResponse<java.util.List<com.hospio.payment.query.TerminalTotalsQuery.Row>> byTerminal(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate f = from == null ? (to == null ? LocalDate.now(LAGOS) : to) : from;
        LocalDate t = to == null ? f : to;
        if (t.isBefore(f)) {
            throw new com.hospio.common.exception.BadRequestException("The end date is before the start date");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(f, t) + 1 > 93) {
            throw new com.hospio.common.exception.BadRequestException("Choose up to 93 days at a time");
        }
        return ApiResponse.ok(terminalTotals.outletPayments(com.hospio.common.tenancy.TenantContext.requireHotel(), f.atStartOfDay(LAGOS).toInstant(),
                t.plusDays(1).atStartOfDay(LAGOS).toInstant()));
    }

    /**
     * Before the desk picks cash: does cash here need a till ({@code needed}), and is it open? {@code role} is the desk the
     * payment is taken for (as on POST /payments); without it, the caller's first paying desk.
     */
    @GetMapping("/cash-till")
    public ApiResponse<PaymentService.CashTill> cashTill(@RequestParam(required = false) com.hospio.common.enums.Role role) {
        return ApiResponse.ok(payments.cashTill(role));
    }

    @GetMapping("/{id}")
    public ApiResponse<PaymentResponse> get(@PathVariable String id) {
        return ApiResponse.ok(payments.get(id));
    }

    @PostMapping
    public ApiResponse<PaymentResponse> request(@Valid @RequestBody PaymentRequestDto request) {
        PaymentResponse p = payments.request(request);
        // The automatic WhatsApp receipt's outlook (null for Don't send), so the desk is told whether it will really go.
        p = p.withReceipt(receipts.automatic(p.id()));
        return ApiResponse.ok(p.status().name().equals("APPROVED") ? "Approved" : "Sent to the POS", p);
    }

    @PostMapping("/{id}/verify")
    public ApiResponse<PaymentResponse> verify(@PathVariable String id) {
        return ApiResponse.ok("Payment confirmed", payments.verify(id));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<PaymentResponse> retry(@PathVariable String id) {
        PaymentResponse p = payments.retry(id);
        return ApiResponse.ok(p.status().name().equals("PROCESSING") ? "Sent to the POS again" : "The POS did not answer", p);
    }

    @PostMapping("/{id}/receipt/resend")
    @PreAuthorize("@access.manager() or @access.atLeast('FINANCE','EDIT')")
    public ApiResponse<ReceiptService.Resent> resendReceipt(@PathVariable String id) {
        ReceiptService.Resent r = receipts.resend(id);
        return ApiResponse.ok(r.message(), r);
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<PaymentResponse> cancel(@PathVariable String id) {
        return ApiResponse.ok("Payment cancelled", payments.cancel(id));
    }

    @PostMapping("/{id}/refunds")
    public ApiResponse<Refund> requestRefund(@PathVariable String id, @Valid @RequestBody RefundRequestDto request) {
        return ApiResponse.ok("Refund requested", refunds.request(id, request));
    }

    @GetMapping("/refunds")
    @PreAuthorize("@access.manager()")
    public ApiResponse<?> refunds(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                  @RequestParam(required = false) String status) {
        if (!Paging.requested(page, size)) {
            return ApiResponse.ok(lists.allRefunds());
        }
        return ApiResponse.ok(lists.refunds(page, size, status));
    }

    @PostMapping("/refunds/{refundId}/approve")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Refund> approveRefund(@PathVariable String refundId) {
        return ApiResponse.ok("Refund approved", refunds.approve(refundId));
    }

    @PostMapping("/refunds/{refundId}/decline")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Refund> declineRefund(@PathVariable String refundId) {
        return ApiResponse.ok("Refund declined", refunds.decline(refundId));
    }

    @GetMapping("/disputes")
    @PreAuthorize("@access.manager()")
    public ApiResponse<?> disputes(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                   @RequestParam(required = false) String status) {
        if (!Paging.requested(page, size)) {
            return ApiResponse.ok(lists.allDisputes());
        }
        return ApiResponse.ok(lists.disputes(page, size, status));
    }

    @PostMapping("/{id}/disputes")
    public ApiResponse<Dispute> openDispute(@PathVariable String id, @Valid @RequestBody DisputeRequest request) {
        return ApiResponse.ok("Dispute opened", refunds.openDispute(id, request));
    }

    @PostMapping("/disputes/{disputeId}/resolve")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Dispute> resolveDispute(@PathVariable String disputeId, @Valid @RequestBody ResolveDisputeRequest request) {
        return ApiResponse.ok("Dispute resolved", refunds.resolveDispute(disputeId, request));
    }
}

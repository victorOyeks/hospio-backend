package com.hospio.payment.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.payment.dto.DisputeRequest;
import com.hospio.payment.dto.PaymentRequestDto;
import com.hospio.payment.dto.PaymentResponse;
import com.hospio.payment.dto.RefundRequestDto;
import com.hospio.payment.dto.ResolveDisputeRequest;
import com.hospio.payment.entity.Dispute;
import com.hospio.payment.entity.Refund;
import com.hospio.payment.service.PaymentService;
import com.hospio.payment.service.RefundService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Taking payment (8.x), My payments & Verify (9.x), and Manager · Payments. */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService payments;
    private final RefundService refunds;

    @GetMapping
    public ApiResponse<List<PaymentResponse>> mine() {
        return ApiResponse.ok(payments.mine());
    }

    @GetMapping("/{id}")
    public ApiResponse<PaymentResponse> get(@PathVariable String id) {
        return ApiResponse.ok(payments.get(id));
    }

    @PostMapping
    public ApiResponse<PaymentResponse> request(@Valid @RequestBody PaymentRequestDto request) {
        PaymentResponse p = payments.request(request);
        return ApiResponse.ok(p.status().name().equals("APPROVED") ? "Approved" : "Sent to the POS", p);
    }

    @PostMapping("/{id}/verify")
    public ApiResponse<PaymentResponse> verify(@PathVariable String id) {
        return ApiResponse.ok("Payment confirmed", payments.verify(id));
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
    public ApiResponse<List<Refund>> refunds() {
        return ApiResponse.ok(refunds.list());
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
    public ApiResponse<List<Dispute>> disputes() {
        return ApiResponse.ok(refunds.disputes());
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

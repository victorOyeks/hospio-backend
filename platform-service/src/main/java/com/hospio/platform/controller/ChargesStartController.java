package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.platform.service.ChargesStartService;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Charges start on" (docs/plan-monthly-invoices.md): the admin portal reads it (every platform role) and money admins change it; the hotel
 * app shows the owner and managers whether they are free or when charges start; messaging-service asks whether guest messages are free.
 */
@RestController
@RequiredArgsConstructor
public class ChargesStartController {



    private final ChargesStartService starts;

    @Operation(summary = "Admin: every business's charges start date (the Free / Billing from tags); a business not listed is free")
    @GetMapping("/admin/billing/charges-start")
    @PreAuthorize("@access.platform()")
    public ApiResponse<ChargesStartService.Rows> rows() {
        return ApiResponse.ok(starts.rows());
    }

    @Operation(summary = "Admin: a business's charges start date, who set it and its history")
    @GetMapping("/admin/businesses/{businessId}/charges-start")
    @PreAuthorize("@access.platform()")
    public ApiResponse<ChargesStartService.View> view(@PathVariable String businessId) {
        return ApiResponse.ok(starts.view(businessId));
    }

    @Operation(summary = "Money admins: what a new date (none = clear) would do to the days not on an invoice yet")
    @GetMapping("/admin/businesses/{businessId}/charges-start/preview")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<ChargesStartService.Preview> preview(@PathVariable String businessId,
                                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(starts.preview(businessId, date));
    }

    @Operation(summary = "Money admins: set, move or clear (null) the charges start date, with a reason; an earlier date that bills free days needs confirmEarlier")
    @PutMapping("/admin/businesses/{businessId}/charges-start")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<ChargesStartService.View> set(@PathVariable String businessId, @RequestBody ChargesStartService.SetRequest request) {
        ChargesStartService.View v = starts.set(businessId, request);
        return ApiResponse.ok(v.chargesStartOn() == null ? "Charges start date cleared: free" : "Charges start on " + v.chargesStartOn(), v);
    }

    @Operation(summary = "Owner and managers: free, or when charges start (the Billing & invoices banner)")
    @GetMapping("/billing/charges")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<ChargesStartService.OwnerView> charges() {
        return ApiResponse.ok(starts.forOwner());
    }

    @Operation(summary = "Internal: a business's charges start date and whether today is free (messaging prices guest messages at NGN 0 then)")
    @GetMapping("/internal/billing/charges-start/{businessId}")
    public ApiResponse<ChargesStartService.InternalView> internal(@PathVariable String businessId) {
        return ApiResponse.ok(starts.internal(businessId));
    }
}

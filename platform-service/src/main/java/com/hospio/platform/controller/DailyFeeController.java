package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.platform.service.DailyFeeService;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Hospio's daily fee (docs/plan-daily-fees.md): the owner's Hospio fees screen, and the admin portal's view. Nothing is debited: each day is billed on the monthly invoice. */
@RestController
@RequiredArgsConstructor
public class DailyFeeController {

    private final DailyFeeService fees;

    @Operation(summary = "Owner: the business's daily fees (default the last 30 days), newest first, each with its sales lines")
    @GetMapping("/owner/fees/daily")
    @PreAuthorize("@access.owner()")
    public ApiResponse<List<DailyFeeService.DailyFeeView>> owner(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(fees.forOwner(from, to));
    }

    @Operation(summary = "Manager (read-only): the current hotel's daily fees (default the last 30 days), newest first, each with its sales lines")
    @GetMapping("/fees/daily")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<List<DailyFeeService.DailyFeeView>> hotel(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(fees.forHotel(com.hospio.common.tenancy.TenantContext.requireHotel(), from, to));
    }

    @Operation(summary = "Admin: daily fees of a business, a hotel, or every hotel")
    @GetMapping("/admin/fees/daily")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<List<DailyFeeService.DailyFeeView>> admin(@RequestParam(required = false) String businessId, @RequestParam(required = false) String hotelId,
                                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(fees.forAdmin(businessId, hotelId, from, to));
    }

    @Operation(summary = "Admin: every hotel's fee state (POS paused, latest day) in one call")
    @GetMapping("/admin/fees/hotels")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<List<DailyFeeService.HotelFeeState>> hotelStates() {
        return ApiResponse.ok(fees.hotelStates());
    }

    @Operation(summary = "Admin: work a day out now for every hotel billed daily (what the 00:05 job does); days already done are left alone")
    @PostMapping("/admin/fees/daily/run")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<DailyFeeService.RunResult> run(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(fees.runDay(date));
    }

    @Operation(summary = "Admin: work a shadow or no-fee day out again")
    @PostMapping("/admin/fees/daily/{id}/redo")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<DailyFeeService.DailyFeeView> redo(@PathVariable String id) {
        return ApiResponse.ok("Worked out again", fees.redo(id));
    }

    @Operation(summary = "Admin: whether a hotel's POS payments are paused for an unpaid invoice")
    @GetMapping("/admin/businesses/{businessId}/hotels/{hotelId}/fee-collection")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<DailyFeeService.CollectionView> collection(@PathVariable String businessId, @PathVariable String hotelId) {
        return ApiResponse.ok(fees.collection(businessId, hotelId));
    }
}

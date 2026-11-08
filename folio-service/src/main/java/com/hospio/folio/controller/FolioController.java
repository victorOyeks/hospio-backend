package com.hospio.folio.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.folio.dto.ChargeRequest;
import com.hospio.folio.dto.DiscountRequest;
import com.hospio.folio.dto.FolioResponse;
import com.hospio.folio.dto.MoveLinesRequest;
import com.hospio.folio.dto.VoidRequest;
import com.hospio.folio.service.FolioService;
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

/** Front office 4.x · Guest folio; Manager · Folios. */
@RestController
@RequestMapping("/folios")
@RequiredArgsConstructor
public class FolioController {

    private final FolioService service;

    @GetMapping
    public ApiResponse<List<FolioResponse>> open() {
        return ApiResponse.ok(service.open());
    }

    @GetMapping("/{id}")
    public ApiResponse<FolioResponse> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/by-booking/{bookingId}")
    public ApiResponse<FolioResponse> byBooking(@PathVariable String bookingId) {
        return ApiResponse.ok(service.byBooking(bookingId));
    }

    @GetMapping("/{id}/stay-summary")
    public ApiResponse<String> staySummary(@PathVariable String id) {
        return ApiResponse.ok(service.staySummary(id));
    }

    @PostMapping("/{id}/charges")
    @PreAuthorize("@access.atLeast('FRONT_OFFICE','EDIT')")
    public ApiResponse<FolioResponse> charge(@PathVariable String id, @Valid @RequestBody ChargeRequest request) {
        return ApiResponse.ok("Charge posted", service.postManualCharge(id, request));
    }

    @PostMapping("/{id}/discount")
    @PreAuthorize("@access.manager()")
    public ApiResponse<FolioResponse> discount(@PathVariable String id, @Valid @RequestBody DiscountRequest request) {
        return ApiResponse.ok("Discount applied", service.discount(id, request));
    }

    @PostMapping("/{id}/lines/{lineId}/void")
    @PreAuthorize("@access.manager()")
    public ApiResponse<FolioResponse> voidLine(@PathVariable String id, @PathVariable String lineId, @Valid @RequestBody VoidRequest request) {
        return ApiResponse.ok("Charge voided", service.voidLine(id, lineId, request));
    }

    /** Split (no target) or move charges to another folio or room. Needs Full. */
    @PostMapping("/{id}/move-lines")
    @PreAuthorize("@access.atLeast('FRONT_OFFICE','FULL')")
    public ApiResponse<FolioResponse> moveLines(@PathVariable String id, @Valid @RequestBody MoveLinesRequest request) {
        return ApiResponse.ok("Charges moved", service.moveLines(id, request));
    }
}

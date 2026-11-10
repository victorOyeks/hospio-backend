package com.hospio.fnb.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.fnb.dto.SaleDetail;
import com.hospio.fnb.dto.SalesHistory;
import com.hospio.fnb.dto.SalesSummary;
import com.hospio.fnb.service.SalesHistoryService;
import com.hospio.fnb.service.SalesService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Bar 6.x close shift and the manager's outlet sales: what was sold, by outlet; and the sales history (docs/restaurant-bar.md). */
@RestController
@RequiredArgsConstructor
public class SalesController {

    static final String SEE = "@access.has('KITCHEN') or @access.has('BAR') or @access.managerView() or @access.has('FINANCE')";

    private final SalesService service;
    private final SalesHistoryService history;

    @GetMapping("/sales")
    @PreAuthorize(SEE)
    public ApiResponse<SalesSummary> sales(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                           @RequestParam(required = false) String outletId) {
        return ApiResponse.ok(service.forDay(date, outletId));
    }

    /** Every sale opened in the Lagos days {@code from}..{@code to} (today when omitted), filtered, newest first, with the money. */
    @GetMapping("/sales/history")
    @PreAuthorize(SEE)
    public ApiResponse<SalesHistory> history(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(required = false) String outletId, @RequestParam(required = false) String desk,
                                             @RequestParam(required = false) String status, @RequestParam(required = false) String method,
                                             @RequestParam(required = false) String attendant, @RequestParam(required = false) Boolean mine,
                                             @RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(history.history(new SalesHistoryService.Params(from, to, outletId, desk, status, method, attendant, mine, q, page, size)));
    }

    /** The same filters as CSV text (Full access, managers, owners and finance). */
    @GetMapping("/sales/history/export")
    @PreAuthorize(SEE)
    public ApiResponse<SalesHistoryService.Export> export(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                          @RequestParam(required = false) String outletId, @RequestParam(required = false) String desk,
                                                          @RequestParam(required = false) String status, @RequestParam(required = false) String method,
                                                          @RequestParam(required = false) String attendant, @RequestParam(required = false) Boolean mine,
                                                          @RequestParam(required = false) String q) {
        return ApiResponse.ok(history.export(new SalesHistoryService.Params(from, to, outletId, desk, status, method, attendant, mine, q, null, null)));
    }

    /** Sales still owing money, any day, in the caller's default view (the sidebar count). */
    @GetMapping("/sales/history/unpaid")
    @PreAuthorize(SEE)
    public ApiResponse<SalesHistoryService.UnpaidCount> unpaid(@RequestParam(required = false) String desk) {
        return ApiResponse.ok(history.unpaid(desk));
    }

    @GetMapping("/sales/history/{id}")
    @PreAuthorize(SEE)
    public ApiResponse<SaleDetail> sale(@PathVariable String id) {
        return ApiResponse.ok(history.detail(id));
    }
}

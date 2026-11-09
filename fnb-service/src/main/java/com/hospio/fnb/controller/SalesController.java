package com.hospio.fnb.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.fnb.dto.SalesSummary;
import com.hospio.fnb.service.SalesService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Bar 6.x close shift and the manager's outlet sales: what was sold, by outlet. */
@RestController
@RequiredArgsConstructor
public class SalesController {

    private final SalesService service;

    @GetMapping("/sales")
    @PreAuthorize("@access.has('KITCHEN') or @access.has('BAR') or @access.managerView() or @access.has('FINANCE')")
    public ApiResponse<SalesSummary> sales(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                           @RequestParam(required = false) String outletId) {
        return ApiResponse.ok(service.forDay(date, outletId));
    }
}

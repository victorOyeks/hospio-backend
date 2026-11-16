package com.hospio.fnb.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.fnb.dto.OpenOrdersSummary;
import com.hospio.fnb.dto.OrderResponse;
import com.hospio.fnb.dto.SalesSummary;
import com.hospio.fnb.service.OrderService;
import com.hospio.fnb.service.SalesService;
import io.swagger.v3.oas.annotations.Hidden;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalFnbController {

    private final OrderService orders;
    private final SalesService sales;
    private final com.hospio.fnb.service.SalesReport salesReport;

    /** platform-service's daily fee: this service's share of what the hotel sold that Lagos day (docs/plan-daily-fees.md). */
    @org.springframework.web.bind.annotation.GetMapping("/sales/daily")
    public com.hospio.common.response.ApiResponse<com.hospio.common.billing.DailySales> dailySales(
            @org.springframework.web.bind.annotation.RequestParam String hotelId,
            @org.springframework.web.bind.annotation.RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate date) {
        return com.hospio.common.response.ApiResponse.ok(salesReport.daily(hotelId, date));
    }

    @GetMapping("/orders/{id}")
    public ApiResponse<OrderResponse> order(@PathVariable String id) {
        return ApiResponse.ok(orders.getInternal(id));
    }

    /** payment-service's check before it takes money for an order: hotel, status, settled and what is still due. */
    @GetMapping("/orders/{id}/due")
    public ApiResponse<java.util.Map<String, Object>> due(@PathVariable String id) {
        return ApiResponse.ok(orders.due(id));
    }

    /** The whole balance of the order was paid (by {@code method}, CASH when not said). Normally the PAYMENT_APPROVED listener does this. */
    @PostMapping("/orders/{id}/paid")
    public ApiResponse<OrderResponse> paid(@PathVariable String id, @RequestParam String paymentId, @RequestParam(required = false) String method) {
        return ApiResponse.ok("Paid", orders.markPaid(id, paymentId, method));
    }

    /** finance's night audit: open tabs and unsettled orders per outlet. */
    @GetMapping("/orders/open")
    public ApiResponse<OpenOrdersSummary> open(@RequestParam String hotelId,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(orders.openOrders(hotelId, date));
    }

    /** finance's today screen and the night-audit report: what every outlet sold on a day. */
    @GetMapping("/sales")
    public ApiResponse<SalesSummary> sales(@RequestParam String hotelId, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(sales.forHotel(hotelId, date));
    }
}

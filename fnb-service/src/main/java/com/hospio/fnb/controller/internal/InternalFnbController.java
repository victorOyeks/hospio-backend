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

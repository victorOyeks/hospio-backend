package com.hospio.payment.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.payment.dto.AdminPaymentDtos.HotelOverview;
import com.hospio.payment.dto.AdminPaymentDtos.Summary;
import com.hospio.payment.dto.PaymentResponse;
import com.hospio.payment.service.AdminPaymentQueryService;
import com.hospio.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Hidden;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalPaymentController {

    private final PaymentService payments;
    private final AdminPaymentQueryService queries;

    @GetMapping("/payments/{id}")
    public ApiResponse<PaymentResponse> get(@PathVariable String id) {
        return ApiResponse.ok(payments.get(id));
    }

    @GetMapping("/payments/by-booking/{bookingId}")
    public ApiResponse<List<PaymentResponse>> byBooking(@PathVariable String bookingId) {
        return ApiResponse.ok(payments.forBooking(bookingId));
    }

    @GetMapping("/payments/by-folio/{folioId}")
    public ApiResponse<List<PaymentResponse>> byFolio(@PathVariable String folioId) {
        return ApiResponse.ok(payments.forFolio(folioId));
    }

    /** platform-service fee statements: approved payments of a business in [from, to], with per-hotel totals. */
    @GetMapping("/admin/summary")
    public ApiResponse<Summary> summary(@RequestParam String businessId,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(queries.summary(businessId, from, to));
    }

    /** platform-service Hotels tab: today's approved payments at the hotel. */
    @GetMapping("/admin/hotel-overview")
    public ApiResponse<HotelOverview> hotelOverview(@RequestParam String hotelId) {
        return ApiResponse.ok(queries.hotelOverview(hotelId));
    }
}

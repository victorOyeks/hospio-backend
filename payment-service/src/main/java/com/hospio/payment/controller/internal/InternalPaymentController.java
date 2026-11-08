package com.hospio.payment.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.payment.dto.PaymentResponse;
import com.hospio.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalPaymentController {

    private final PaymentService payments;

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
}

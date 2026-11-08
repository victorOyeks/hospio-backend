package com.hospio.payment.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The POS provider calls back here (public path; the provider adapter authenticates the payload). */
@Hidden
@RestController
@RequestMapping("/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final PaymentService payments;

    @PostMapping("/pos/{provider}")
    public ApiResponse<Void> pos(@PathVariable String provider, @RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers) {
        payments.onWebhook(provider, body, headers);
        return ApiResponse.ok("Received", null);
    }
}

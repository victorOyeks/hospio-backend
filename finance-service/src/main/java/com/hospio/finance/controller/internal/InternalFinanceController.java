package com.hospio.finance.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.finance.service.MoneyService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service reads of finance-service (internal API key): payment-service asks whether a desk's till is open before cash. */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalFinanceController {

    private final MoneyService service;

    @GetMapping("/tills/open")
    public ApiResponse<MoneyService.OpenTill> openTill(@RequestParam String hotelId, @RequestParam String role) {
        return ApiResponse.ok(service.openTillOf(hotelId, role));
    }
}

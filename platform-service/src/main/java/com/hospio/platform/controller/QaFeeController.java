package com.hospio.platform.controller;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.ApiResponse;
import com.hospio.platform.service.DailyFeeService;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * QA only (docs/plan-monthly-invoices.md, "Testing on staging"): exists only when QA_FEE_SIMULATION=true (staging), SUPER_ADMIN only.
 * Works out one hotel's daily fee for a date without touching other hotels. (The simulated debit of the daily-debit trial is gone with
 * the debit itself; the monthly invoice's QA endpoints are in QaBillingController.)
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@ConditionalOnProperty(name = "hospio.qa.fee-simulation", havingValue = "true")
public class QaFeeController {

    private final DailyFeeService dailyFees;

    public record WorkOutRequest(String businessId, String hotelId, LocalDate date) {
    }

    @Operation(summary = "QA: work out one hotel's daily fee for a date (only that hotel)")
    @PostMapping("/admin/qa/fees/daily/work-out")
    public ApiResponse<DailyFeeService.DailyFeeView> workOut(@RequestBody WorkOutRequest request) {
        if (request == null || request.businessId() == null || request.hotelId() == null || request.date() == null) {
            throw new BadRequestException("Send businessId, hotelId and date");
        }
        return ApiResponse.ok("Worked out", dailyFees.workOutOne(request.businessId(), request.hotelId(), request.date()));
    }
}

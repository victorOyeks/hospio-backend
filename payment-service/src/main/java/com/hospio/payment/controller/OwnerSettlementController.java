package com.hospio.payment.controller;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.payment.entity.Settlement;
import com.hospio.payment.repository.SettlementRepository;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner portal · Payments &amp; payouts (docs/owner-portal.md): the business's own settlements (card and transfer money
 * the providers collected, less their fee and Hospio360's share), by day and provider, with whether it has been paid out.
 */
@RestController
@RequestMapping("/payments/settlements")
@RequiredArgsConstructor
public class OwnerSettlementController {

    private final SettlementRepository settlements;

    @Operation(summary = "Owner: the business's settlements between two Lagos days (default this month), newest first")
    @GetMapping
    @PreAuthorize("@access.owner()")
    @Transactional(readOnly = true)
    public ApiResponse<List<Settlement>> mine(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
        LocalDate start = from == null ? today.withDayOfMonth(1) : from;
        LocalDate end = to == null ? today : to;
        if (end.isBefore(start)) {
            throw new BadRequestException("The end date is before the start date");
        }
        if (start.plusDays(366).isBefore(end)) {
            throw new BadRequestException("Pick at most a year");
        }
        return ApiResponse.ok(settlements.findByBusinessIdAndPeriodDateBetweenOrderByPeriodDateDesc(TenantContext.requireBusiness(), start, end));
    }
}

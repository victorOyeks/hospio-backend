package com.hospio.platform.controller;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.entity.DailyFee;
import com.hospio.platform.entity.FeeCollection;
import com.hospio.platform.repository.DailyFeeRepository;
import com.hospio.platform.repository.FeeCollectionRepository;
import com.hospio.platform.service.DailyFeeService;
import com.hospio.platform.service.FeeDebitRunner;
import io.swagger.v3.oas.annotations.Operation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * QA only (docs/plan-marketers.md, "Testing on staging"): exists only when QA_FEE_SIMULATION=true (staging), SUPER_ADMIN only. Lets QA run
 * the daily fee end to end without a real Moniepoint/OPay debit: a hotel is switched to the SIMULATED provider (the 06:00 run never debits
 * it), a single day is worked out for that hotel only, and each debit try is answered by hand (APPROVED with an amount, or FAILED). The
 * answer goes through the same bookkeeping as a real one: carry-over, device share, marketer commission.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@ConditionalOnProperty(name = "hospio.qa.fee-simulation", havingValue = "true")
public class QaFeeController {

    private final FeeCollectionRepository collections;
    private final DailyFeeRepository fees;
    private final DailyFeeService dailyFees;
    private final FeeDebitRunner runner;
    private final TransactionTemplate tx;

    public record SimulatedDebitRequest(String businessId, boolean on) {
    }

    public record WorkOutRequest(String businessId, String hotelId, LocalDate date) {
    }

    public record DebitAnswer(String result, BigDecimal amount) {
    }

    @Operation(summary = "QA: switch a hotel's daily fee debit to the simulated provider (on) or back to shadow mode (off)")
    @PutMapping("/admin/qa/hotels/{hotelId}/simulated-debit")
    public ApiResponse<DailyFeeService.CollectionView> simulated(@PathVariable String hotelId, @RequestBody SimulatedDebitRequest request) {
        if (request == null || request.businessId() == null) {
            throw new BadRequestException("Send businessId");
        }
        var user = CurrentUser.require();
        TenantContext.runWith(Tenant.system("fee-qa"), () -> {
            tx.executeWithoutResult(st -> {
                FeeCollection c = collections.findByHotelId(hotelId)
                        .orElseGet(() -> FeeCollection.builder().businessId(request.businessId()).hotelId(hotelId).build());
                if (c.getProvider() != null && !FeeDebitRunner.SIMULATED.equals(c.getProvider()) && c.isDebitEnabled()) {
                    throw new BadRequestException("This hotel has a real debit switched on; QA simulation is only for test hotels");
                }
                c.setProvider(request.on() ? FeeDebitRunner.SIMULATED : null);
                c.setDebitEnabled(request.on());
                c.setChangedBy(user.getUserId());
                c.setChangedByName(user.getFullName() + " (QA simulation)");
                c.setChangedAt(Instant.now());
                collections.save(c);
            });
            return null;
        });
        return ApiResponse.ok(request.on() ? "Simulated debit on (QA)" : "Simulated debit off", new DailyFeeService.CollectionView(hotelId,
                request.on() ? FeeDebitRunner.SIMULATED : null, request.on(), false, user.getFullName() + " (QA simulation)", Instant.now(), java.util.List.of()));
    }

    @Operation(summary = "QA: work out one hotel's daily fee for a date (only that hotel)")
    @PostMapping("/admin/qa/fees/daily/work-out")
    public ApiResponse<DailyFeeService.DailyFeeView> workOut(@RequestBody WorkOutRequest request) {
        if (request == null || request.businessId() == null || request.hotelId() == null || request.date() == null) {
            throw new BadRequestException("Send businessId, hotelId and date");
        }
        return ApiResponse.ok("Worked out", dailyFees.workOutOne(request.businessId(), request.hotelId(), request.date()));
    }

    @Operation(summary = "QA: answer one debit try of a simulated hotel's day: APPROVED (amount, default what is left) or FAILED")
    @PostMapping("/admin/qa/fees/daily/{id}/debit")
    public ApiResponse<DailyFeeService.DailyFeeView> debit(@PathVariable String id, @RequestBody DebitAnswer answer) {
        String result = answer == null || answer.result() == null ? "APPROVED" : answer.result().toUpperCase(java.util.Locale.ROOT);
        if (!result.equals("APPROVED") && !result.equals("FAILED")) {
            throw new BadRequestException("result is APPROVED or FAILED");
        }
        DailyFee f = TenantContext.runWith(Tenant.system("fee-qa"), () -> fees.findById(id).orElseThrow(() -> new ResourceNotFoundException("Daily fee not found")));
        BigDecimal amount = answer == null || answer.amount() == null ? f.remaining() : answer.amount();
        if (result.equals("APPROVED") && amount.signum() <= 0) {
            throw new BadRequestException("Nothing is left to collect on this day");
        }
        String status = runner.simulate(f, result, amount);
        DailyFee after = TenantContext.runWith(Tenant.system("fee-qa"), () -> fees.findById(id).orElseThrow());
        return ApiResponse.ok("Debit " + status.toLowerCase(java.util.Locale.ROOT), dailyFees.view(after));
    }
}

package com.hospio.finance.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.finance.dto.FinanceRequests;
import com.hospio.finance.dto.MoneyToday;
import com.hospio.finance.entity.BankDeposit;
import com.hospio.finance.entity.Budget;
import com.hospio.finance.entity.Expense;
import com.hospio.finance.entity.NightAudit;
import com.hospio.finance.entity.RevenueEntry;
import com.hospio.finance.entity.Till;
import com.hospio.finance.entity.TillEntry;
import com.hospio.finance.service.MoneyService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Finance 1.x-4.x and Manager · Financials. */
@RestController
@RequiredArgsConstructor
public class FinanceController {

    private final MoneyService service;

    @GetMapping("/today")
    public ApiResponse<MoneyToday> today(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(service.today(date));
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, BigDecimal>> summary(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.summary(from, to));
    }

    @PostMapping("/payments/{paymentId}/match")
    public ApiResponse<RevenueEntry> match(@PathVariable String paymentId, @RequestParam(defaultValue = "true") boolean matched) {
        return ApiResponse.ok("Updated", service.match(paymentId, matched));
    }

    @GetMapping("/expenses")
    public ApiResponse<List<Expense>> expenses() {
        return ApiResponse.ok(service.expenses());
    }

    @PostMapping("/expenses")
    public ApiResponse<Expense> recordExpense(@Valid @RequestBody FinanceRequests.ExpenseRequest request) {
        Expense e = service.recordExpense(request);
        return ApiResponse.ok(e.getStatus() == Expense.Status.PENDING_APPROVAL ? "Sent for approval" : "Expense recorded", e);
    }

    @PostMapping("/expenses/{id}/paid")
    public ApiResponse<Expense> paid(@PathVariable String id) {
        return ApiResponse.ok("Marked paid", service.markPaid(id));
    }

    @GetMapping("/budgets")
    public ApiResponse<List<Budget>> budgets(@RequestParam String month) {
        return ApiResponse.ok(service.budgets(month));
    }

    @PutMapping("/budgets")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Budget> saveBudget(@Valid @RequestBody FinanceRequests.BudgetRequest request) {
        return ApiResponse.ok("Budget saved", service.saveBudget(request));
    }

    @GetMapping("/tills")
    public ApiResponse<List<Till>> tills() {
        return ApiResponse.ok(service.tillsToday());
    }

    @PostMapping("/tills")
    public ApiResponse<Till> openTill(@Valid @RequestBody FinanceRequests.OpenTillRequest request) {
        return ApiResponse.ok("Till opened", service.openTill(request));
    }

    @GetMapping("/tills/{id}/entries")
    public ApiResponse<List<TillEntry>> tillEntries(@PathVariable String id) {
        return ApiResponse.ok(service.tillEntries(id));
    }

    @PostMapping("/tills/{id}/close")
    public ApiResponse<Till> closeTill(@PathVariable String id, @Valid @RequestBody FinanceRequests.CloseTillRequest request) {
        return ApiResponse.ok("Till closed", service.closeTill(id, request));
    }

    @GetMapping("/deposits")
    public ApiResponse<List<BankDeposit>> deposits() {
        return ApiResponse.ok(service.deposits());
    }

    @PostMapping("/deposits")
    public ApiResponse<BankDeposit> bankCash(@Valid @RequestBody FinanceRequests.BankCashRequest request) {
        return ApiResponse.ok("Cash banked", service.bankCash(request));
    }

    @GetMapping("/night-audit")
    public ApiResponse<NightAudit> audit(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(service.audit(date));
    }

    @GetMapping("/night-audit/past")
    public ApiResponse<List<NightAudit>> pastAudits() {
        return ApiResponse.ok(service.pastAudits());
    }

    @PutMapping("/night-audit/checklist")
    public ApiResponse<NightAudit> checklist(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                             @RequestBody FinanceRequests.ChecklistRequest request) {
        return ApiResponse.ok("Saved", service.saveChecklist(date, request));
    }

    @PostMapping("/night-audit/close")
    public ApiResponse<NightAudit> closeDay(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                            @RequestBody(required = false) FinanceRequests.CloseDayRequest request) {
        return ApiResponse.ok("Day closed", service.closeDay(date, request));
    }
}

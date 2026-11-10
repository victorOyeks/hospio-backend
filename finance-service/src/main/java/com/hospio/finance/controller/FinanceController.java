package com.hospio.finance.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.finance.dto.FinanceRequests;
import com.hospio.finance.dto.MoneyToday;
import com.hospio.finance.dto.NightAuditView;
import com.hospio.finance.entity.BankDeposit;
import com.hospio.finance.entity.Budget;
import com.hospio.finance.entity.Expense;
import com.hospio.finance.entity.NightAudit;
import com.hospio.finance.entity.RevenueEntry;
import com.hospio.finance.entity.Till;
import com.hospio.finance.entity.TillEntry;
import com.hospio.finance.service.ExpenseQueryService;
import com.hospio.finance.service.MoneyService;
import com.hospio.finance.service.NightAuditService;
import com.hospio.finance.service.ReportingService;
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

/**
 * Finance 1.x-4.x and Manager · Financials. Reads of hotel-wide money are for a manager (or owner, or a support look) and the
 * finance role; writes are for a manager, an owner or Finance EDIT. Staff keep the routes for their own till and spend.
 */
@RestController
@RequiredArgsConstructor
public class FinanceController {

    private static final String READ = "@access.managerView() or @access.atLeast('FINANCE','VIEW')";
    private static final String WRITE = "@access.manager() or @access.atLeast('FINANCE','EDIT')";

    private final MoneyService service;
    private final NightAuditService nightAudit;
    private final ReportingService reporting;
    private final ExpenseQueryService expenseQuery;

    @GetMapping("/today")
    @PreAuthorize(READ)
    public ApiResponse<MoneyToday> today(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(service.today(date));
    }

    @GetMapping("/summary")
    @PreAuthorize(READ)
    public ApiResponse<Map<String, BigDecimal>> summary(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.summary(from, to));
    }

    /** Revenue between two dates by the desk that took it, with each share of the total. */
    @GetMapping("/income")
    @PreAuthorize(READ)
    public ApiResponse<List<ReportingService.IncomeSource>> income(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reporting.income(from, to));
    }

    /** What the restaurant and bar sold on a day, by outlet (fnb's day summary), for the manager and owner screens. Income above is money received. */
    @GetMapping("/sales")
    @PreAuthorize(READ)
    public ApiResponse<com.fasterxml.jackson.databind.JsonNode> sales(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(reporting.outletSales(date));
    }

    /** Per day {date, revenue, expenses} for the range. */
    @GetMapping("/series")
    @PreAuthorize(READ)
    public ApiResponse<List<ReportingService.DayPoint>> series(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reporting.series(from, to));
    }

    /** The day's payments, paid expenses, deposits and till movements, newest first. */
    @GetMapping("/transactions")
    @PreAuthorize(READ)
    public ApiResponse<List<ReportingService.Transaction>> transactions(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(reporting.transactions(date));
    }

    /** The owner's view across every hotel of the business. */
    @GetMapping("/owner/summary")
    @PreAuthorize("@access.owner()")
    public ApiResponse<ReportingService.OwnerSummary> ownerSummary(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(reporting.ownerSummary(from, to));
    }

    @PostMapping("/payments/{paymentId}/match")
    @PreAuthorize(WRITE)
    public ApiResponse<RevenueEntry> match(@PathVariable String paymentId, @RequestParam(defaultValue = "true") boolean matched) {
        return ApiResponse.ok("Updated", service.match(paymentId, matched));
    }

    @GetMapping("/expenses")
    @PreAuthorize(READ)
    public ApiResponse<?> expenses(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                   @RequestParam(required = false) String tab, @RequestParam(required = false) String category,
                                   @RequestParam(required = false) String q, @RequestParam(required = false) String sort,
                                   @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        // With page or size: a page with tab counts and totals (docs/pagination.md). Without: the old full list, for scripts and reports.
        if (!com.hospio.common.paging.Paging.requested(page, size)) {
            return ApiResponse.ok(service.expenses(status, from, to));
        }
        return ApiResponse.ok(expenseQuery.page(new ExpenseQueryService.Filter(tab, status, category, q, from, to), page, size, sort));
    }

    @PostMapping("/expenses")
    public ApiResponse<Expense> recordExpense(@Valid @RequestBody FinanceRequests.ExpenseRequest request) {
        Expense e = service.recordExpense(request);
        return ApiResponse.ok(e.getStatus() == Expense.Status.PENDING_APPROVAL ? "Sent for approval" : "Expense recorded", e);
    }

    /** The requester answers the manager's question on their expense. */
    @PostMapping("/expenses/{id}/answer")
    public ApiResponse<Expense> answer(@PathVariable String id, @Valid @RequestBody FinanceRequests.AnswerRequest request) {
        return ApiResponse.ok("Answer sent", service.answer(id, request.note()));
    }

    @PostMapping("/expenses/{id}/paid")
    @PreAuthorize(WRITE)
    public ApiResponse<Expense> paid(@PathVariable String id, @RequestBody(required = false) FinanceRequests.PaidRequest request) {
        return ApiResponse.ok("Marked paid", service.markPaid(id, request == null ? null : request.paidFrom()));
    }

    @GetMapping("/budgets")
    @PreAuthorize(READ)
    public ApiResponse<List<Budget>> budgets(@RequestParam String month) {
        return ApiResponse.ok(service.budgets(month));
    }

    @PutMapping("/budgets")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Budget> saveBudget(@Valid @RequestBody FinanceRequests.BudgetRequest request) {
        return ApiResponse.ok("Budget saved", service.saveBudget(request));
    }

    @GetMapping("/tills")
    @PreAuthorize(READ)
    public ApiResponse<List<Till>> tills() {
        return ApiResponse.ok(service.tillsToday());
    }

    /** The open till of the caller's paying role, or null. */
    @GetMapping("/tills/mine")
    public ApiResponse<Till> myTill() {
        return ApiResponse.ok(service.myTill());
    }

    /** The caller's role's tills closed in the last 7 days. */
    @GetMapping("/tills/mine/closed")
    public ApiResponse<List<Till>> myClosedTills() {
        return ApiResponse.ok(service.myClosedTills());
    }

    @PostMapping("/tills")
    public ApiResponse<Till> openTill(@Valid @RequestBody FinanceRequests.OpenTillRequest request) {
        return ApiResponse.ok("Till opened", service.openTill(request));
    }

    @GetMapping("/tills/{id}/entries")
    public ApiResponse<List<TillEntry>> tillEntries(@PathVariable String id) {
        return ApiResponse.ok(service.tillEntries(id));
    }

    @PostMapping("/tills/{id}/movements")
    public ApiResponse<TillEntry> movement(@PathVariable String id, @Valid @RequestBody FinanceRequests.MovementRequest request) {
        return ApiResponse.ok("Recorded", service.addMovement(id, request));
    }

    @PostMapping("/tills/{id}/close")
    public ApiResponse<Till> closeTill(@PathVariable String id, @Valid @RequestBody FinanceRequests.CloseTillRequest request) {
        return ApiResponse.ok("Till closed", service.closeTill(id, request));
    }

    @PostMapping("/tills/{id}/handover")
    public ApiResponse<MoneyService.Handover> handover(@PathVariable String id, @Valid @RequestBody FinanceRequests.HandoverRequest request) {
        return ApiResponse.ok("Till handed over", service.handover(id, request));
    }

    @GetMapping("/deposits")
    @PreAuthorize(READ)
    public ApiResponse<List<BankDeposit>> deposits() {
        return ApiResponse.ok(service.deposits());
    }

    @PostMapping("/deposits")
    @PreAuthorize(WRITE)
    public ApiResponse<BankDeposit> bankCash(@Valid @RequestBody FinanceRequests.BankCashRequest request) {
        return ApiResponse.ok("Cash banked", service.bankCash(request));
    }

    /** The audit if any, plus the computed checklist and the day's counts. Never creates a row. */
    @GetMapping("/night-audit")
    @PreAuthorize(READ)
    public ApiResponse<NightAuditView> audit(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(nightAudit.view(date));
    }

    @GetMapping("/night-audit/past")
    @PreAuthorize(READ)
    public ApiResponse<?> pastAudits(@RequestParam(required = false) String status,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        // With page or size: a page, newest business day first. Without: the old full list, for scripts.
        if (!com.hospio.common.paging.Paging.requested(page, size)) {
            return ApiResponse.ok(nightAudit.past());
        }
        return ApiResponse.ok(expenseQuery.audits(status, from, to, page, size));
    }

    @PutMapping("/night-audit/checklist")
    @PreAuthorize("@access.manager() or @access.atLeast('FINANCE','FULL')")
    public ApiResponse<NightAudit> checklist(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                             @RequestBody FinanceRequests.ChecklistRequest request) {
        return ApiResponse.ok("Saved", nightAudit.saveChecklist(date, request));
    }

    @PostMapping("/night-audit/close")
    @PreAuthorize("@access.manager() or @access.atLeast('FINANCE','FULL')")
    public ApiResponse<NightAuditView> closeDay(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                @RequestBody(required = false) FinanceRequests.CloseDayRequest request) {
        return ApiResponse.ok("Day closed", nightAudit.close(date, request));
    }
}

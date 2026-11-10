package com.hospio.finance.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.finance.service.BillService;
import com.hospio.finance.service.BudgetMath;
import com.hospio.finance.service.ExpenseCategories;
import com.hospio.finance.service.MoneyService;
import java.time.LocalDate;
import java.util.List;
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
 * Manager · Financials: the hotel's expense categories, budget against actual and bills (docs/manager-leftovers.md). Categories are read by
 * anyone at the hotel (every expense form lists them) and changed by a manager; budgets read by managers and Finance; bills read by managers,
 * the owner and Finance and changed by them with EDIT. The services refuse with a sentence that says who may.
 */
@RestController
@RequiredArgsConstructor
public class PayablesController {

    private static final String READ = "@access.managerView() or @access.atLeast('FINANCE','VIEW')";

    private final ExpenseCategories categories;
    private final MoneyService money;
    private final BillService bills;

    @GetMapping("/expense-categories")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<ExpenseCategories.CategoryView>> categories(@RequestParam(defaultValue = "false") boolean all) {
        TenantContext.requireHotel();
        return ApiResponse.ok(categories.list(all));
    }

    @PostMapping("/expense-categories")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ExpenseCategories.CategoryView> addCategory(@RequestBody ExpenseCategories.CategoryRequest request) {
        ExpenseCategories.CategoryView c = categories.add(request);
        return ApiResponse.ok(c.label() + " added", c);
    }

    @PutMapping("/expense-categories/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ExpenseCategories.CategoryView> saveCategory(@PathVariable String id, @RequestBody ExpenseCategories.CategoryRequest request) {
        ExpenseCategories.CategoryView c = categories.update(id, request);
        return ApiResponse.ok(c.active() ? c.label() + " saved" : c.label() + " archived", c);
    }

    @GetMapping("/budgets/overview")
    @PreAuthorize(READ)
    public ApiResponse<BudgetMath.Overview> budgetOverview(@RequestParam String month) {
        return ApiResponse.ok(money.budgetOverview(month));
    }

    @GetMapping("/bills")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageWithCounts<BillService.BillView>> bills(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                                    @RequestParam(required = false) String sort, @RequestParam(required = false) Integer page,
                                                                    @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(bills.page(status, q, from, to, sort, page, size));
    }

    @GetMapping("/bills/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<BillService.BillView> bill(@PathVariable String id) {
        return ApiResponse.ok(bills.get(id));
    }

    @PostMapping("/bills")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<BillService.BillView> addBill(@RequestBody BillService.BillRequest request) {
        BillService.BillView b = bills.create(request);
        return ApiResponse.ok("Bill from " + b.payee() + " added", b);
    }

    @PutMapping("/bills/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<BillService.BillView> saveBill(@PathVariable String id, @RequestBody BillService.BillRequest request) {
        return ApiResponse.ok("Bill saved", bills.update(id, request));
    }

    @PostMapping("/bills/{id}/paid")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<BillService.Paid> payBill(@PathVariable String id, @RequestBody(required = false) BillService.PayRequest request) {
        BillService.Paid p = bills.pay(id, request);
        return ApiResponse.ok(p.next() == null ? "Bill paid · expense recorded" : "Bill paid · expense recorded · next one due " + p.next().dueDate(), p);
    }

    @PostMapping("/bills/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<BillService.BillView> cancelBill(@PathVariable String id) {
        return ApiResponse.ok("Bill cancelled", bills.cancel(id));
    }
}

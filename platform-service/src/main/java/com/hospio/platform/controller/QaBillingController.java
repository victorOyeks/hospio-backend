package com.hospio.platform.controller;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.ApiResponse;
import com.hospio.platform.service.MonthlyInvoiceService;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * QA only (docs/plan-monthly-invoices.md, "Testing on staging"): exists only when QA_FEE_SIMULATION=true (staging, never production),
 * SUPER_ADMIN only, and (with BILLING_QA_ONLY=true) for QA businesses only. Issues one business's invoice with any issue date (so the due
 * date, grace and restriction fall on chosen days), runs the 06:00 and 08:00 steps for a chosen date, and shows a notice's email text.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@ConditionalOnProperty(name = "hospio.qa.fee-simulation", havingValue = "true")
public class QaBillingController {

    private final MonthlyInvoiceService invoices;
    private final com.hospio.platform.service.SuspensionService suspensions;

    public record IssueRequest(String businessId, String month, LocalDate issuedOn) {
    }

    public record DayRequest(String businessId, LocalDate date) {
    }

    @Operation(summary = "QA: issue one business's invoice for a month (the current month allowed) with a chosen issue date")
    @PostMapping("/admin/qa/billing/issue")
    public ApiResponse<MonthlyInvoiceService.InvoiceView> issue(@RequestBody IssueRequest r) {
        if (r == null || r.businessId() == null || r.month() == null || r.issuedOn() == null) {
            throw new BadRequestException("Send businessId, month (YYYY-MM) and issuedOn");
        }
        return ApiResponse.ok(invoices.issueOne(r.businessId(), YearMonth.parse(r.month()), r.issuedOn()));
    }

    @Operation(summary = "QA: run the 06:00 restriction step and the 08:00 reminders as on a chosen date, for one business")
    @PostMapping("/admin/qa/billing/run-day")
    public ApiResponse<Map<String, Object>> runDay(@RequestBody DayRequest r) {
        if (r == null || r.businessId() == null || r.date() == null) {
            throw new BadRequestException("Send businessId and date");
        }
        MonthlyInvoiceService.StepRun restrict = invoices.restrictDue(r.date(), r.businessId());
        MonthlyInvoiceService.StepRun remind = invoices.remind(r.date(), r.businessId());
        return ApiResponse.ok(Map.of("restriction", restrict, "reminders", remind));
    }

    @Operation(summary = "QA: the subject and text a notice would email (ISSUED, REMINDER, DUE_TODAY, OVERDUE, FINAL_WARNING, RESTRICTED)")
    @GetMapping("/admin/qa/billing/invoices/{id}/notice")
    public ApiResponse<Map<String, String>> notice(@PathVariable String id, @RequestParam String kind) {
        var n = invoices.preview(id, kind.toUpperCase(java.util.Locale.ROOT));
        return ApiResponse.ok(Map.of("subject", n.subject(), "text", n.text(), "kind", n.emailKind().name()));
    }

    @Operation(summary = "QA: the subject and text of a suspension email for a business as it is now (NOTICE, SUSPENDED, REACTIVATED)")
    @GetMapping("/admin/qa/suspension/{businessId}/email")
    public ApiResponse<Map<String, String>> suspensionEmail(@PathVariable String businessId, @RequestParam String kind) {
        var m = suspensions.preview(businessId, kind);
        return ApiResponse.ok(Map.of("subject", m.subject(), "text", m.text(), "bellTitle", m.bellTitle(), "bellBody", m.bellBody()));
    }
}

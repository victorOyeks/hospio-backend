package com.hospio.platform.controller;

import com.hospio.common.billing.BillingHold;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.ApiResponse;
import com.hospio.platform.client.PlatformPaystackClient;
import com.hospio.platform.entity.MonthlyInvoicePayment;
import com.hospio.platform.service.DailyFeeService;
import com.hospio.platform.service.InvoicePdf;
import com.hospio.platform.service.MonthlyInvoiceService;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hospio360's monthly invoice (docs/plan-monthly-invoices.md). The hotel app: the banner state, the owner's Billing & invoices (managers
 * read-only, their own locations' lines), Pay now through Paystack, PDFs. The admin portal: every invoice, its reminder log, and the money
 * actions (record an offline payment, extend once, credit note or adjustment, void and reissue).
 */
@RestController
@RequiredArgsConstructor
public class BillingController {

    private final MonthlyInvoiceService invoices;
    private final DailyFeeService dailyFees;
    private final InvoicePdf pdf;

    public record PayRequest(String returnUrl) {
    }

    // ---- the hotel app ------------------------------------------------------------------------------------------

    @Operation(summary = "Anyone signed in at a business: the billing banner (owners and managers: due, overdue, final notice; everyone: paused)")
    @GetMapping("/billing/status")
    public ApiResponse<MonthlyInvoiceService.BillingStatus> status() {
        return ApiResponse.ok(invoices.status());
    }

    @Operation(summary = "Owner (managers read-only): the business's monthly invoices, newest first")
    @GetMapping("/billing/invoices")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<List<MonthlyInvoiceService.InvoiceRow>> list() {
        return ApiResponse.ok(invoices.forMe());
    }

    @Operation(summary = "One invoice with its sections, lines and payments (a manager sees their own locations' lines)")
    @GetMapping("/billing/invoices/{id}")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<MonthlyInvoiceService.InvoiceView> one(@PathVariable String id) {
        return ApiResponse.ok(invoices.mineView(id));
    }

    @Operation(summary = "The invoice's platform fee day by day (each location's daily sales, rate, fee and device share)")
    @GetMapping("/billing/invoices/{id}/days")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<List<DailyFeeService.DailyFeeView>> days(@PathVariable String id) {
        return ApiResponse.ok(invoices.days(id, dailyFees));
    }

    @Operation(summary = "The day-by-day breakdown as CSV")
    @GetMapping("/billing/invoices/{id}/days.csv")
    @PreAuthorize("@access.managerView()")
    public ResponseEntity<String> daysCsv(@PathVariable String id) {
        MonthlyInvoiceService.InvoiceView v = invoices.mineView(id);
        return csv(v.number() + "-days.csv", daysCsv(invoices.days(id, dailyFees)));
    }

    @Operation(summary = "The invoice as a PDF (Oyeks Global letterhead), with the day-by-day platform fee")
    @GetMapping("/billing/invoices/{id}/pdf")
    @PreAuthorize("@access.managerView()")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable String id) {
        MonthlyInvoiceService.InvoiceView v = invoices.mineView(id);
        return pdf(v.number() + ".pdf", pdf.invoice(v, invoices.days(id, dailyFees), invoices.payLinkOf(id)));
    }

    @Operation(summary = "A payment's receipt as a PDF")
    @GetMapping("/billing/invoices/{id}/payments/{paymentId}/receipt")
    @PreAuthorize("@access.managerView()")
    public ResponseEntity<byte[]> receiptPdf(@PathVariable String id, @PathVariable String paymentId) {
        MonthlyInvoiceService.InvoiceView v = invoices.mineView(id);
        MonthlyInvoicePayment p = invoices.paymentOf(id, paymentId);
        return pdf(p.getReceiptNumber() + ".pdf", pdf.receipt(v, p));
    }

    @Operation(summary = "Owner: Pay now, a Paystack checkout for the full amount outstanding (card, transfer, USSD)")
    @PostMapping("/billing/invoices/{id}/pay")
    @PreAuthorize("@access.owner()")
    public ApiResponse<PlatformPaystackClient.Checkout> pay(@PathVariable String id, @RequestBody(required = false) PayRequest r) {
        return ApiResponse.ok(invoices.payOnline(id, r == null ? null : r.returnUrl()));
    }

    @Operation(summary = "Back from Paystack: the payment is checked with Paystack and recorded once (the webhook does the same)")
    @PostMapping("/billing/payments/confirm")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<MonthlyInvoiceService.InvoiceView> confirm(@RequestParam String reference) {
        return ApiResponse.ok(invoices.confirm(reference));
    }

    // ---- Hospio360 admins -----------------------------------------------------------------------------------------

    @Operation(summary = "Admin: invoices across businesses (status DUE, DUE_SOON, OVERDUE, RESTRICTED, PAID, CARRIED_FORWARD, VOID; month YYYY-MM)")
    @GetMapping("/admin/billing/invoices")
    @PreAuthorize("@access.platform()")
    public ApiResponse<MonthlyInvoiceService.InvoiceList> adminList(@RequestParam(required = false) String status, @RequestParam(required = false) String month,
                                                                   @RequestParam(required = false) String businessId) {
        return ApiResponse.ok(invoices.adminList(status, month, businessId));
    }

    @Operation(summary = "Admin: the invoices as CSV (same filters)")
    @GetMapping("/admin/billing/invoices/export")
    @PreAuthorize("@access.platform()")
    public ResponseEntity<String> adminCsv(@RequestParam(required = false) String status, @RequestParam(required = false) String month,
                                           @RequestParam(required = false) String businessId) {
        return csv("hospio360-invoices" + (month == null ? "" : "-" + month) + ".csv", invoices.adminCsv(status, month, businessId));
    }

    @Operation(summary = "Admin: one invoice with lines, payments and the reminder / action log")
    @GetMapping("/admin/billing/invoices/{id}")
    @PreAuthorize("@access.platform()")
    public ApiResponse<MonthlyInvoiceService.InvoiceView> adminOne(@PathVariable String id) {
        return ApiResponse.ok(invoices.adminView(id));
    }

    @GetMapping("/admin/billing/invoices/{id}/days")
    @PreAuthorize("@access.platform()")
    public ApiResponse<List<DailyFeeService.DailyFeeView>> adminDays(@PathVariable String id) {
        return ApiResponse.ok(invoices.daysAdmin(id, dailyFees));
    }

    @GetMapping("/admin/billing/invoices/{id}/pdf")
    @PreAuthorize("@access.platform()")
    public ResponseEntity<byte[]> adminPdf(@PathVariable String id) {
        MonthlyInvoiceService.InvoiceView v = invoices.adminView(id);
        return pdf(v.number() + ".pdf", pdf.invoice(v, invoices.daysAdmin(id, dailyFees), invoices.payLinkOf(id)));
    }

    @GetMapping("/admin/billing/invoices/{id}/payments/{paymentId}/receipt")
    @PreAuthorize("@access.platform()")
    public ResponseEntity<byte[]> adminReceipt(@PathVariable String id, @PathVariable String paymentId) {
        MonthlyInvoiceService.InvoiceView v = invoices.adminView(id);
        MonthlyInvoicePayment p = invoices.paymentOfAdmin(id, paymentId);
        return pdf(p.getReceiptNumber() + ".pdf", pdf.receipt(v, p));
    }

    @Operation(summary = "Money roles: record a payment received outside Paystack (bank transfer), part payments allowed")
    @PostMapping("/admin/billing/invoices/{id}/payments")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<MonthlyInvoiceService.InvoiceView> record(@PathVariable String id, @RequestBody MonthlyInvoiceService.PaymentRequest r) {
        return ApiResponse.ok("Payment recorded", invoices.recordPayment(id, r));
    }

    @Operation(summary = "Money roles: the one extension (new last day and reason); lifts a restriction now")
    @PostMapping("/admin/billing/invoices/{id}/extend")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<MonthlyInvoiceService.InvoiceView> extend(@PathVariable String id, @RequestBody MonthlyInvoiceService.ExtendRequest r) {
        return ApiResponse.ok("Extended", invoices.extend(id, r));
    }

    @Operation(summary = "Money roles: a credit note (negative amount) or an adjustment (positive) with its reason")
    @PostMapping("/admin/billing/invoices/{id}/adjust")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<MonthlyInvoiceService.InvoiceView> adjust(@PathVariable String id, @RequestBody MonthlyInvoiceService.AdjustRequest r) {
        return ApiResponse.ok("Adjusted", invoices.adjust(id, r));
    }

    @Operation(summary = "Money roles: void an unpaid invoice (reason), optionally reissuing it now from the same charges")
    @PostMapping("/admin/billing/invoices/{id}/void")
    @PreAuthorize(AdminFeesController.MONEY)
    public ApiResponse<MonthlyInvoiceService.InvoiceView> voidInvoice(@PathVariable String id, @RequestBody MonthlyInvoiceService.VoidRequest r) {
        return ApiResponse.ok("Voided", invoices.voidInvoice(id, r));
    }

    @Operation(summary = "SUPER_ADMIN: issue a month's invoices now (default last month), for every business or one")
    @PostMapping("/admin/billing/invoices/run")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<Object> run(@RequestParam(required = false) String month, @RequestParam(required = false) String businessId) {
        LocalDate today = LocalDate.now(MonthlyInvoiceService.LAGOS);
        YearMonth m = month == null ? YearMonth.from(today).minusMonths(1) : YearMonth.parse(month);
        if (!m.isBefore(YearMonth.from(today))) {
            throw new BadRequestException("Only a month that has ended can be invoiced");
        }
        return businessId == null ? ApiResponse.ok(invoices.issueAll(m, today)) : ApiResponse.ok(invoices.issueOne(businessId, m, today));
    }

    @Operation(summary = "Admin: a business's billing state (business page)")
    @GetMapping("/admin/businesses/{businessId}/billing")
    @PreAuthorize("@access.platform()")
    public ApiResponse<MonthlyInvoiceService.BusinessBilling> businessBilling(@PathVariable String businessId) {
        return ApiResponse.ok(invoices.businessBilling(businessId));
    }

    // ---- internal ---------------------------------------------------------------------------------------------------

    @Operation(summary = "Internal: is the business restricted for an unpaid invoice (services check before new sales, bookings, card/transfer)")
    @GetMapping("/internal/billing/holds/{businessId}")
    public ApiResponse<BillingHold.Hold> hold(@PathVariable String businessId) {
        return ApiResponse.ok(invoices.hold(businessId));
    }

    @Operation(summary = "Internal: Paystack's webhook (received by messaging-service) for a monthly invoice payment")
    @PostMapping("/internal/billing/paystack-confirm")
    public ApiResponse<MonthlyInvoiceService.InvoiceView> internalConfirm(@RequestParam String reference) {
        return ApiResponse.ok(invoices.confirm(reference));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    static ResponseEntity<byte[]> pdf(String name, byte[] body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").body(body);
    }

    static ResponseEntity<String> csv(String name, String body) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv")).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(body);
    }

    static String daysCsv(List<DailyFeeService.DailyFeeView> days) {
        StringBuilder s = new StringBuilder("location,sale_day,sales,rate_pct,fee,device_rate_pct,device_fee,status\n");
        for (DailyFeeService.DailyFeeView d : days) {
            String loc = d.hotelName() == null ? "" : d.hotelName();
            if (loc.contains(",") || loc.contains("\"")) {
                loc = "\"" + loc.replace("\"", "\"\"") + "\"";
            }
            s.append(loc).append(',').append(d.date()).append(',').append(d.sales().toPlainString()).append(',').append(d.rate().stripTrailingZeros().toPlainString())
                    .append(',').append(d.fee().toPlainString()).append(',').append(d.deviceRate() == null ? "" : d.deviceRate().stripTrailingZeros().toPlainString())
                    .append(',').append(d.deviceFee() == null ? "" : d.deviceFee().toPlainString()).append(',').append(d.status()).append('\n');
        }
        return s.toString();
    }
}

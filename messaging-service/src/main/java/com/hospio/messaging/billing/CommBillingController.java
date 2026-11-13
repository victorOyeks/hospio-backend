package com.hospio.messaging.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.paging.Paging;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.messaging.entity.DeliveryRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Guest messages as a paid service (docs/guest-message-billing.md).
 * Hotel side (owner, manager; X-Hotel-Id): /billing/messages… Hospio360 side (platform): /billing/messages/admin…
 * Paystack's webhook: /webhooks/paystack (signed with the secret key).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class CommBillingController {

    private final CommBilling billing;
    private final PaystackClient paystack;
    private final ObjectMapper json = new ObjectMapper();

    /** One billed (or billable) message as the invoice detail lists it. */
    public record MessageRow(String id, Instant at, String kind, String label, String channel, String sentVia, String to, String subject,
                             String status, BigDecimal price) {
        static MessageRow of(DeliveryRecord d) {
            return new MessageRow(d.getId(), d.getSentAt() == null ? d.getCreatedAt() : d.getSentAt(), d.getKind(), CommBilling.label(d.getKind()),
                    d.getChannel(), d.getSentVia(), d.getToAddress(), d.getSubject(), d.getStatus().name(), d.getUnitPrice());
        }
    }

    public record PageOf<T>(List<T> content, int number, int size, long totalElements, int totalPages) {
        static <T> PageOf<T> of(Page<?> p, List<T> rows) {
            return new PageOf<>(rows, p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
        }
    }

    public record EnabledRequest(Boolean enabled) {
    }

    public record PayRequest(String returnUrl) {
    }

    public record PricesRequest(BigDecimal email, BigDecimal sms, BigDecimal whatsapp) {
    }

    public record PlanRequest(String businessId, Boolean enabled, BigDecimal email, BigDecimal sms, BigDecimal whatsapp, Boolean useDefaults) {
    }

    public record IssueRequest(String month, String hotelId, String businessId) {
    }

    public record MarkPaidRequest(String reference, String note) {
    }

    public record SuspendRequest(Boolean suspended, String reason) {
    }

    // ---- the hotel's own -------------------------------------------------------------------------------------------

    @GetMapping("/billing/messages")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<CommBilling.PlanView> plan() {
        return ApiResponse.ok(billing.plan(TenantContext.requireHotel()));
    }

    @PutMapping("/billing/messages")
    @PreAuthorize("@access.manager()")
    public ApiResponse<CommBilling.PlanView> setEnabled(@RequestBody EnabledRequest request) {
        if (request == null || request.enabled() == null) {
            throw new BadRequestException("Say whether guest messages are on (enabled: true or false)");
        }
        String business = TenantContext.current().map(t -> t.businessId()).orElse(CurrentUser.require().getBusinessId());
        CommBilling.PlanView view = billing.setEnabled(business, TenantContext.requireHotel(), request.enabled());
        return ApiResponse.ok(request.enabled() ? "Guest messages are on" : "Guest messages are off", view);
    }

    @GetMapping("/billing/messages/this-month")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<PageOf<MessageRow>> thisMonth(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Page<DeliveryRecord> p = billing.thisMonthMessages(TenantContext.requireHotel(), Paging.page(page), Paging.size(size));
        return ApiResponse.ok(PageOf.of(p, p.getContent().stream().map(MessageRow::of).toList()));
    }

    @GetMapping("/billing/messages/invoices")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<PageOf<CommInvoice>> invoices(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Page<CommInvoice> p = billing.invoicesOf(TenantContext.requireHotel(), Paging.page(page), Paging.size(size));
        return ApiResponse.ok(PageOf.of(p, p.getContent()));
    }

    @GetMapping("/billing/messages/invoices/{id}")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<CommBilling.InvoiceView> invoice(@PathVariable String id) {
        return ApiResponse.ok(billing.invoice(id, hotelOrPlatform()));
    }

    @GetMapping("/billing/messages/invoices/{id}/messages")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<PageOf<MessageRow>> invoiceMessages(@PathVariable String id, @RequestParam(required = false) Integer page,
                                                           @RequestParam(required = false) Integer size) {
        Page<DeliveryRecord> p = billing.invoiceMessages(id, hotelOrPlatform(), Paging.page(page), Paging.size(size));
        return ApiResponse.ok(PageOf.of(p, p.getContent().stream().map(MessageRow::of).toList()));
    }

    @PostMapping("/billing/messages/invoices/{id}/pay")
    @PreAuthorize("@access.manager()")
    public ApiResponse<PaystackClient.Checkout> pay(@PathVariable String id, @RequestBody(required = false) PayRequest request) {
        return ApiResponse.ok(billing.pay(id, TenantContext.requireHotel(), request == null ? null : request.returnUrl()));
    }

    /** The payer is back from Paystack: confirm it and settle the invoice (the webhook does the same). */
    @PostMapping("/billing/messages/payments/{reference}/confirm")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<CommInvoice> confirm(@PathVariable String reference) {
        CommInvoice inv = billing.confirm(reference, CurrentUser.require().getFullName());
        return ApiResponse.ok("Invoice " + inv.getNumber() + " paid", inv);
    }

    // ---- Hospio360's side --------------------------------------------------------------------------------------------

    @GetMapping("/billing/messages/admin/prices")
    @PreAuthorize("@access.platform()")
    public ApiResponse<CommBilling.Prices> defaults() {
        return ApiResponse.ok(billing.defaults());
    }

    @PutMapping("/billing/messages/admin/prices")
    @PreAuthorize("@access.platform()")
    public ApiResponse<CommBilling.Prices> setDefaults(@RequestBody PricesRequest r) {
        return ApiResponse.ok("Default prices saved", billing.setDefaults(r.email(), r.sms(), r.whatsapp()));
    }

    /** The plans of these hotels (a business's hotels, as the admin portal lists them). */
    @GetMapping("/billing/messages/admin/plans")
    @PreAuthorize("@access.platform()")
    public ApiResponse<List<CommBilling.PlanView>> plans(@RequestParam String hotelIds) {
        return ApiResponse.ok(billing.plansOf(null, Arrays.stream(hotelIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()));
    }

    @PutMapping("/billing/messages/admin/plans/{hotelId}")
    @PreAuthorize("@access.platform()")
    public ApiResponse<CommBilling.PlanView> setPlan(@PathVariable String hotelId, @RequestBody PlanRequest r) {
        if (r.businessId() == null || r.businessId().isBlank()) {
            throw new BadRequestException("Say which business the hotel belongs to (businessId)");
        }
        return ApiResponse.ok("Guest messages saved", billing.setPlan(r.businessId(), hotelId, r.enabled(), r.email(), r.sms(), r.whatsapp(),
                Boolean.TRUE.equals(r.useDefaults())));
    }

    @PostMapping("/billing/messages/admin/plans/{hotelId}/suspension")
    @PreAuthorize("@access.platform()")
    public ApiResponse<CommBilling.PlanView> suspend(@PathVariable String hotelId, @RequestBody SuspendRequest r) {
        return ApiResponse.ok(billing.setSuspended(hotelId, Boolean.TRUE.equals(r.suspended()), r.reason()));
    }

    @GetMapping("/billing/messages/admin/invoices")
    @PreAuthorize("@access.platform()")
    public ApiResponse<PageOf<CommInvoice>> allInvoices(@RequestParam(required = false) String status, @RequestParam(required = false) String month,
                                                        @RequestParam(required = false) String hotelId, @RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size) {
        Page<CommInvoice> p = billing.allInvoices(status, month, hotelId, Paging.page(page), Paging.size(size));
        return ApiResponse.ok(PageOf.of(p, p.getContent()));
    }

    /** Issues a month's invoices now (normally the 1st does it): all hotels, or one. Default: last month. */
    @PostMapping("/billing/messages/admin/invoices/issue")
    @PreAuthorize("@access.platform()")
    public ApiResponse<List<CommInvoice>> issue(@RequestBody(required = false) IssueRequest r) {
        YearMonth month = r == null || r.month() == null || r.month().isBlank() ? YearMonth.now(CommBilling.LAGOS).minusMonths(1) : YearMonth.parse(r.month().trim());
        List<CommInvoice> made = r != null && r.hotelId() != null && !r.hotelId().isBlank()
                ? billing.issue(r.hotelId().trim(), r.businessId(), month).map(List::of).orElse(List.of())
                : billing.issueAll(month);
        return ApiResponse.ok(made.size() + " invoice" + (made.size() == 1 ? "" : "s") + " issued for " + month, made);
    }

    @PostMapping("/billing/messages/admin/invoices/{id}/mark-paid")
    @PreAuthorize("@access.platform()")
    public ApiResponse<CommInvoice> markPaid(@PathVariable String id, @RequestBody(required = false) MarkPaidRequest r) {
        return ApiResponse.ok("Marked paid", billing.settle(id, "MANUAL", r == null ? null : r.reference(), CurrentUser.require().getFullName(),
                r == null ? null : r.note()));
    }

    /** Runs the daily overdue check now (normally 09:00). */
    @PostMapping("/billing/messages/admin/overdue-check")
    @PreAuthorize("@access.platform()")
    public ApiResponse<Map<String, Integer>> overdue() {
        return ApiResponse.ok(Map.of("suspended", billing.suspendOverdue()));
    }

    // ---- Paystack's webhook ----------------------------------------------------------------------------------------

    @PostMapping("/webhooks/paystack")
    public ResponseEntity<Void> paystackWebhook(@RequestHeader(value = "x-paystack-signature", required = false) String signature,
                                                @RequestBody String body) {
        if (!paystack.signed(body, signature)) {
            return ResponseEntity.status(401).build();
        }
        try {
            JsonNode e = json.readTree(body);
            if ("charge.success".equals(e.path("event").asText())) {
                String reference = e.path("data").path("reference").asText(null);
                if (reference != null && reference.startsWith("MSG-")) {
                    billing.confirm(reference, "Paystack");
                }
            }
        } catch (Exception ex) {
            log.warn("Paystack webhook not applied: {}", ex.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    private static String hotelOrPlatform() {
        return CurrentUser.require().isPlatform() ? null : TenantContext.requireHotel();
    }
}

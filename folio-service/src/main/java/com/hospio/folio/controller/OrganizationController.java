package com.hospio.folio.controller;

import com.hospio.common.paging.Paging;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.response.PageWithCounts;
import com.hospio.folio.dto.OrganizationViews;
import com.hospio.folio.service.OrganizationQueryService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import com.hospio.folio.dto.InvoiceRequest;
import com.hospio.folio.dto.MemberRequest;
import com.hospio.folio.dto.OrgPaymentRequest;
import com.hospio.folio.dto.OrganizationRequest;
import com.hospio.folio.entity.Folio;
import com.hospio.folio.entity.Invoice;
import com.hospio.folio.entity.Organization;
import com.hospio.folio.entity.OrganizationMember;
import com.hospio.folio.entity.OrganizationPayment;
import com.hospio.folio.service.OrganizationService;
import com.hospio.folio.service.OutboundService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService service;
    private final OutboundService outbound;
    private final OrganizationQueryService query;

    /** Put on hold or back to active: {@code ?status=ON_HOLD|ACTIVE}. */
    @PutMapping("/{id}/status")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Organization> status(@PathVariable String id, @RequestParam String status) {
        return ApiResponse.ok("ON_HOLD".equals(status) ? "Account on hold" : "Account active", service.setStatus(id, status));
    }

    /** Remove a staff member from the account ({@code active=false}) or allow them again. */
    @PutMapping("/{id}/members/{memberId}/active")
    @PreAuthorize("@access.manager()")
    public ApiResponse<OrganizationMember> memberActive(@PathVariable String id, @PathVariable String memberId, @RequestParam boolean active) {
        return ApiResponse.ok(active ? "Allowed again" : "Removed from the account", service.setMemberActive(id, memberId, active));
    }

    @PostMapping("/{id}/statement")
    @PreAuthorize("@access.manager() or @access.atLeast('FINANCE','EDIT')")
    public ApiResponse<OutboundService.Sent> statement(@PathVariable String id) {
        OutboundService.Sent s = outbound.statement(id);
        return ApiResponse.ok("Statement sent to " + s.to(), s);
    }

    @PostMapping("/{id}/reminder")
    @PreAuthorize("@access.manager() or @access.atLeast('FINANCE','EDIT')")
    public ApiResponse<OutboundService.Sent> reminder(@PathVariable String id) {
        OutboundService.Sent s = outbound.reminder(id);
        return ApiResponse.ok("Reminder sent to " + s.to(), s);
    }

    /**
     * The hotel's accounts. With {@code page} or {@code size} (page from 0, size 1 to 100, 25 by default): one page of accounts with
     * their figures, searched ({@code q}: name or contact) and filtered ({@code status} ACTIVE or ON_HOLD), A to Z unless {@code sort}
     * (name|createdAt|creditLimit|status[,asc|desc]), with counts per status and the outstanding total. Without either: every account,
     * as before (operations scripts read it).
     */
    @GetMapping
    public ApiResponse<?> list(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                               @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                               @RequestParam(required = false) String sort) {
        if (Paging.requested(page, size)) {
            return ApiResponse.ok(query.page(q, status, page, size, sort));
        }
        return ApiResponse.ok(service.list());
    }

    /** The hotel's accounts at a glance: accounts, staff who can book, stays this month, owed, open and overdue invoices, on hold. */
    @GetMapping("/overview")
    public ApiResponse<OrganizationViews.OrganizationsOverview> overview() {
        return ApiResponse.ok(query.overview());
    }

    /**
     * Every invoice of the hotel, one page at a time (page, size, sort dueDate|number|amount|createdAt), latest due first, filtered by
     * {@code organizationId}, {@code status} (OPEN, OVERDUE, SENT, PART_PAID, PAID, DRAFT) and {@code q} (number or account name).
     */
    @GetMapping("/invoices")
    public ApiResponse<PageWithCounts<OrganizationViews.InvoiceView>> allInvoices(@RequestParam(required = false) String organizationId,
                                                                                @RequestParam(required = false) String status,
                                                                                @RequestParam(required = false) String q,
                                                                                @RequestParam(required = false) Integer page,
                                                                                @RequestParam(required = false) Integer size,
                                                                                @RequestParam(required = false) String sort) {
        return ApiResponse.ok(query.invoices(organizationId, status, q, page, size, sort));
    }

    /** One account with its figures (owed, staff, stays, invoices, the latest overdue one). */
    @GetMapping("/{id}/summary")
    public ApiResponse<OrganizationViews.OrganizationSummary> summary(@PathVariable String id) {
        return ApiResponse.ok(query.one(id));
    }

    @GetMapping("/{id}")
    public ApiResponse<Organization> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize("@access.manager()")
    public ApiResponse<Organization> create(@Valid @RequestBody OrganizationRequest request) {
        Organization org = service.save(null, request);
        return ApiResponse.ok(org.isCreditSentToOwner() ? "Organization added · the credit limit is waiting for the owner" : "Organization added", org);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Organization> update(@PathVariable String id, @Valid @RequestBody OrganizationRequest request) {
        Organization org = service.save(id, request);
        return ApiResponse.ok(org.isCreditSentToOwner() ? "Saved · the new credit limit is waiting for the owner" : "Saved", org);
    }

    @GetMapping("/{id}/members")
    public ApiResponse<List<OrganizationMember>> members(@PathVariable String id) {
        return ApiResponse.ok(service.members(id));
    }

    @PostMapping("/{id}/members")
    @PreAuthorize("@access.manager()")
    public ApiResponse<OrganizationMember> addMember(@PathVariable String id, @Valid @RequestBody MemberRequest request) {
        return ApiResponse.ok("Added", service.addMember(id, request));
    }

    @PostMapping("/{id}/members/import")
    @PreAuthorize("@access.manager()")
    public ApiResponse<List<OrganizationMember>> importMembers(@PathVariable String id, @RequestBody List<@Valid MemberRequest> rows) {
        return ApiResponse.ok(rows.size() + " staff imported", service.importMembers(id, rows));
    }

    /**
     * The account's stays (folios), latest opened first. With {@code page} or {@code size}: one page filtered by {@code status} (OPEN,
     * CLOSED), {@code from}/{@code to} (Lagos days opened) and {@code q} (guest or room), with counts and the balance and charges over
     * the range. Without either: every stay, as before.
     */
    @GetMapping("/{id}/stays")
    public ApiResponse<?> stays(@PathVariable String id, @RequestParam(required = false) String status,
                                @RequestParam(required = false) String q,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                @RequestParam(required = false) String sort) {
        if (Paging.requested(page, size)) {
            return ApiResponse.ok(query.stays(id, status, q, from, to, page, size, sort));
        }
        return ApiResponse.ok(service.stays(id));
    }

    @GetMapping("/{id}/outstanding")
    public ApiResponse<BigDecimal> outstanding(@PathVariable String id) {
        return ApiResponse.ok(service.outstanding(id));
    }

    /** The account's invoices, latest due first: with {@code page} or {@code size} one page as {@code /invoices} answers; without, all. */
    @GetMapping("/{id}/invoices")
    public ApiResponse<?> invoices(@PathVariable String id, @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        if (Paging.requested(page, size)) {
            return ApiResponse.ok(query.invoices(id, status, q, page, size, sort));
        }
        return ApiResponse.ok(service.invoices(id));
    }

    @PostMapping("/{id}/invoices")
    @PreAuthorize("@access.manager() or @access.atLeast('FINANCE','EDIT')")
    public ApiResponse<Invoice> invoice(@PathVariable String id, @Valid @RequestBody InvoiceRequest request) {
        return ApiResponse.ok("Invoice sent", service.createInvoice(id, request));
    }

    @GetMapping("/{id}/payments")
    public ApiResponse<List<OrganizationPayment>> payments(@PathVariable String id) {
        return ApiResponse.ok(service.payments(id));
    }

    @PostMapping("/{id}/payments")
    @PreAuthorize("@access.manager() or @access.atLeast('FINANCE','EDIT')")
    public ApiResponse<OrganizationPayment> recordPayment(@PathVariable String id, @Valid @RequestBody OrgPaymentRequest request) {
        return ApiResponse.ok("Payment recorded", service.recordPayment(id, request));
    }
}

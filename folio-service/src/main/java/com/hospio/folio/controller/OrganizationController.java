package com.hospio.folio.controller;

import com.hospio.common.response.ApiResponse;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService service;

    @GetMapping
    public ApiResponse<List<Organization>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Organization> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize("@access.manager()")
    public ApiResponse<Organization> create(@Valid @RequestBody OrganizationRequest request) {
        return ApiResponse.ok("Organization added", service.save(null, request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Organization> update(@PathVariable String id, @Valid @RequestBody OrganizationRequest request) {
        return ApiResponse.ok("Saved", service.save(id, request));
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

    @GetMapping("/{id}/stays")
    public ApiResponse<List<Folio>> stays(@PathVariable String id) {
        return ApiResponse.ok(service.stays(id));
    }

    @GetMapping("/{id}/outstanding")
    public ApiResponse<BigDecimal> outstanding(@PathVariable String id) {
        return ApiResponse.ok(service.outstanding(id));
    }

    @GetMapping("/{id}/invoices")
    public ApiResponse<List<Invoice>> invoices(@PathVariable String id) {
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

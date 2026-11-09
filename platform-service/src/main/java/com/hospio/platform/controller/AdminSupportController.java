package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.response.PagedResponse;
import com.hospio.platform.dto.AdminResponses.TicketCommentResponse;
import com.hospio.platform.dto.AdminResponses.TicketDetail;
import com.hospio.platform.dto.AdminResponses.TicketResolved;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.SupportTicket;
import com.hospio.platform.service.SupportService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
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

/** Support (G): the platform's ticket queue. */
@RestController
@RequestMapping("/admin/support/tickets")
@RequiredArgsConstructor
@PreAuthorize("@access.platform()")
public class AdminSupportController {

    private final SupportService support;

    @Operation(summary = "Tickets, paged, filtered by status (OPEN covers IN_PROGRESS too), priority, business, assignee and a search on subject or names")
    @GetMapping
    public ApiResponse<PagedResponse<SupportTicket>> list(@RequestParam(required = false) String status, @RequestParam(required = false) String priority,
                                                          @RequestParam(required = false) String businessId, @RequestParam(required = false) String assignedTo,
                                                          @RequestParam(required = false) String q,
                                                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(support.search(status, priority, businessId, assignedTo, q, page, size));
    }

    @Operation(summary = "One ticket with every comment, internal notes included")
    @GetMapping("/{id}")
    public ApiResponse<TicketDetail> get(@PathVariable String id) {
        return ApiResponse.ok(support.get(id));
    }

    @Operation(summary = "Open a ticket on a business's behalf")
    @PostMapping
    public ApiResponse<SupportTicket> open(@Valid @RequestBody PlatformRequests.TicketRequest request) {
        return ApiResponse.ok("Ticket opened", support.open(request));
    }

    @Operation(summary = "Assign the ticket to an admin; an OPEN ticket becomes IN_PROGRESS")
    @PutMapping("/{id}/assign")
    public ApiResponse<SupportTicket> assign(@PathVariable String id, @Valid @RequestBody PlatformRequests.AssignTicketRequest request) {
        return ApiResponse.ok("Assigned", support.assign(id, request));
    }

    @Operation(summary = "Change the priority (LOW, NORMAL, HIGH, URGENT); URGENT raises a TICKET_URGENT alert")
    @PutMapping("/{id}/priority")
    public ApiResponse<SupportTicket> priority(@PathVariable String id, @Valid @RequestBody PlatformRequests.TicketPriorityRequest request) {
        return ApiResponse.ok("Priority changed", support.setPriority(id, request));
    }

    @Operation(summary = "Add a comment; one that is not internal goes to the opener as a TICKET_UPDATE (echoed under deliveries)")
    @PostMapping("/{id}/comments")
    public ApiResponse<TicketCommentResponse> comment(@PathVariable String id, @Valid @RequestBody PlatformRequests.TicketCommentRequest request) {
        return ApiResponse.ok("Comment added", support.comment(id, request));
    }

    @Operation(summary = "Resolve the ticket; the opener is told (TICKET_UPDATE)")
    @PostMapping("/{id}/resolve")
    public ApiResponse<TicketResolved> resolve(@PathVariable String id, @Valid @RequestBody PlatformRequests.ResolveTicketRequest request) {
        return ApiResponse.ok("Resolved", support.resolve(id, request));
    }

    @Operation(summary = "Reopen a resolved ticket")
    @PostMapping("/{id}/reopen")
    public ApiResponse<SupportTicket> reopen(@PathVariable String id) {
        return ApiResponse.ok("Reopened", support.reopen(id));
    }
}

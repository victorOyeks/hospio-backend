package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.Approval;
import com.hospio.platform.service.ApprovalQueryService;
import com.hospio.platform.service.ApprovalService;
import com.hospio.common.paging.Paging;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Manager · Approvals and Owner · Dashboard · Approvals waiting for you. */
@RestController
@RequestMapping("/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService service;
    private final ApprovalQueryService query;

    @GetMapping
    @PreAuthorize("@access.managerView()")
    public ApiResponse<List<Approval>> pending() {
        return ApiResponse.ok(service.pending());
    }

    /**
     * Every approval of the business. With {@code page} or {@code size}: a page (docs/pagination.md) with {@code tab}
     * (waiting, owner, decided, open), {@code status}, {@code kind}, {@code q}, {@code from}, {@code to}, {@code sort}; counts per tab and
     * the money waiting over the whole filtered range. Without either: the old full list, newest first (scripts read it).
     */
    @GetMapping("/all")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<?> all(@RequestParam(required = false) String tab, @RequestParam(required = false) String status,
                              @RequestParam(required = false) String kind, @RequestParam(required = false) String q,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                              @RequestParam(required = false) String sort, @RequestParam(required = false) Integer page,
                              @RequestParam(required = false) Integer size) {
        if (!Paging.requested(page, size)) {
            return ApiResponse.ok(service.all());
        }
        return ApiResponse.ok(query.page(new ApprovalQueryService.Filter(tab, status, kind, q, from, to), page, size, sort));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<Approval> get(@PathVariable String id) {
        return ApiResponse.ok(query.get(id));
    }

    /** decision: APPROVED, DECLINED or QUESTION (ask the requester something). */
    @PostMapping("/{id}/decide")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Approval> decide(@PathVariable String id, @Valid @RequestBody PlatformRequests.DecisionRequest request) {
        return ApiResponse.ok("Decided", service.decide(id, request));
    }

    /** A manager hands a MANAGER-level approval up to the owner, with a note on why. */
    @PostMapping("/{id}/escalate")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Approval> escalate(@PathVariable String id, @RequestBody(required = false) PlatformRequests.EscalateRequest request) {
        return ApiResponse.ok("Sent to the owner", service.escalate(id, request == null ? null : request.note()));
    }
}

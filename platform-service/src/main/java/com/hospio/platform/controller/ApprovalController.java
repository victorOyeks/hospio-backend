package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.Approval;
import com.hospio.platform.service.ApprovalService;
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

    @GetMapping
    @PreAuthorize("@access.manager()")
    public ApiResponse<List<Approval>> pending() {
        return ApiResponse.ok(service.pending());
    }

    @GetMapping("/all")
    @PreAuthorize("@access.manager()")
    public ApiResponse<List<Approval>> all() {
        return ApiResponse.ok(service.all());
    }

    /** decision: APPROVED, DECLINED or QUESTION (ask the requester something). */
    @PostMapping("/{id}/decide")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Approval> decide(@PathVariable String id, @Valid @RequestBody PlatformRequests.DecisionRequest request) {
        return ApiResponse.ok("Decided", service.decide(id, request));
    }
}

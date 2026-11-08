package com.hospio.platform.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.platform.entity.Approval;
import com.hospio.platform.repository.ApprovalRepository;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalPlatformController {

    private final ApprovalRepository approvals;

    @GetMapping("/approvals/{id}")
    public ApiResponse<Approval> approval(@PathVariable String id) {
        return ApiResponse.ok(approvals.findById(id).orElseThrow(() -> new com.hospio.common.exception.ResourceNotFoundException("Approval not found")));
    }
}

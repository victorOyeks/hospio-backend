package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.platform.jobs.AuditRetentionJob;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * QA only (docs/data-retention.md): runs tonight's audit log retention now. Exists only when hospio.qa.tools is on (QA_TOOLS, by default
 * the staging QA_FEE_SIMULATION switch; never production) and the worker runs; SUPER_ADMIN only.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@ConditionalOnProperty(name = "hospio.qa.tools", havingValue = "true")
public class QaRetentionController {

    private final AuditRetentionJob job;

    @Operation(summary = "QA: delete audit logs past retention now (what the 03:30 job does)")
    @PostMapping("/admin/qa/retention/run")
    public ApiResponse<AuditRetentionJob.Result> run() {
        return ApiResponse.ok(job.run());
    }
}

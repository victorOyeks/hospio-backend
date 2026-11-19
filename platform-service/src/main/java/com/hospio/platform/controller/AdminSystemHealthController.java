package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.platform.system.SystemHealthDtos.Report;
import com.hospio.platform.system.SystemHealthService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform → System health (docs/system-health.md): the server, every Hospio service, the database, Kafka, Redis, certificates and
 * 7 days of trends. Read-only. Super admins and Support (who take the calls when something is down); Finance admins, marketers and
 * every business role are refused. The alert limits are saved through PUT /admin/settings/system (Super admin).
 */
@RestController
@RequestMapping("/admin/system-health")
@RequiredArgsConstructor
public class AdminSystemHealthController {

    private final SystemHealthService health;

    @Operation(summary = "SUPER_ADMIN or SUPPORT: the server's health now (kept 15 s; fresh=true asks for a new look, at most every 5 s)")
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','SUPPORT')")
    public ApiResponse<Report> report(@RequestParam(defaultValue = "false") boolean fresh) {
        return ApiResponse.ok(health.report(fresh));
    }
}

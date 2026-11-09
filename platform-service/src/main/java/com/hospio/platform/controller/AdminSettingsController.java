package com.hospio.platform.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.platform.dto.AdminResponses.Integration;
import com.hospio.platform.service.IntegrationsService;
import com.hospio.platform.service.PlatformSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Settings and integrations: every platform user reads, SUPER_ADMIN writes. */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminSettingsController {

    private final PlatformSettingsService settings;
    private final IntegrationsService integrations;

    @Operation(summary = "Every settings group (general, fees, security), saved values laid over the defaults")
    @GetMapping("/settings")
    @PreAuthorize("@access.platform()")
    public ApiResponse<Map<String, Map<String, Object>>> settings() {
        return ApiResponse.ok(settings.all());
    }

    @Operation(summary = "SUPER_ADMIN: save a group's values (body = the group's map); returns the merged group")
    @PutMapping("/settings/{group}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<Map<String, Object>> update(@PathVariable String group, @RequestBody Map<String, Object> values) {
        return ApiResponse.ok("Settings saved", settings.update(group, values));
    }

    @Operation(summary = "Integrations and their status: database, Kafka, payment provider, WhatsApp, email, SMS, Smile ID")
    @GetMapping("/integrations")
    @PreAuthorize("@access.platform()")
    public ApiResponse<List<Integration>> integrations() {
        return ApiResponse.ok(integrations.list());
    }
}

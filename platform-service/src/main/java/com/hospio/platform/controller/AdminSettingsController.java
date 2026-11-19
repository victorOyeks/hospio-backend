package com.hospio.platform.controller;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.ApiResponse;
import com.hospio.platform.dto.AdminResponses.Integration;
import com.hospio.platform.service.IntegrationsService;
import com.hospio.platform.service.PlatformSettingsService;
import com.hospio.platform.settings.FeeSettings;
import com.hospio.platform.settings.GeneralSettings;
import com.hospio.platform.settings.PlatformSettings;
import com.hospio.platform.settings.SecuritySettings;
import com.hospio.platform.settings.SystemHealthSettings;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
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
    public ApiResponse<PlatformSettings> settings() {
        return ApiResponse.ok(settings.all());
    }

    @Operation(summary = "SUPER_ADMIN: save the general group (fields left out keep their value); returns the merged group")
    @PutMapping("/settings/general")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<GeneralSettings> updateGeneral(@RequestBody GeneralSettings values) {
        return ApiResponse.ok("Settings saved", settings.update(values));
    }

    @Operation(summary = "SUPER_ADMIN: save the fees group (fields left out keep their value); returns the merged group")
    @PutMapping("/settings/fees")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<FeeSettings> updateFees(@RequestBody FeeSettings values) {
        return ApiResponse.ok("Settings saved", settings.update(values));
    }

    @Operation(summary = "SUPER_ADMIN: save the security group (fields left out keep their value); returns the merged group")
    @PutMapping("/settings/security")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<SecuritySettings> updateSecurity(@RequestBody SecuritySettings values) {
        return ApiResponse.ok("Settings saved", settings.update(values));
    }

    @Operation(summary = "SUPER_ADMIN: save the system health alert limits (fields left out keep their value); returns the merged group")
    @PutMapping("/settings/system")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<SystemHealthSettings> updateSystem(@RequestBody SystemHealthSettings values) {
        return ApiResponse.ok("Settings saved", settings.update(values));
    }

    @Operation(summary = "Any other group: refused")
    @PutMapping("/settings/{group}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<Void> updateUnknown(@PathVariable String group) {
        throw new BadRequestException("Unknown settings group: " + group);
    }

    /** Connection status (moved from GET /admin/integrations, which is now the integration settings: AdminIntegrationsController). */
    @Operation(summary = "Integrations and their status: database, Kafka, payment provider, WhatsApp, email, SMS")
    @GetMapping("/integrations/status")
    @PreAuthorize("@access.platform()")
    public ApiResponse<List<Integration>> integrations() {
        return ApiResponse.ok(integrations.list());
    }
}

package com.hospio.platform.controller;

import com.hospio.common.integrations.settings.IntegrationProvider;
import com.hospio.common.integrations.settings.IntegrationTestResult;
import com.hospio.common.response.ApiResponse;
import com.hospio.platform.client.IntegrationTestClient;
import com.hospio.platform.dto.IntegrationDtos.IntegrationUpdate;
import com.hospio.platform.dto.IntegrationDtos.IntegrationView;
import com.hospio.platform.service.IntegrationSettingsService;
import io.swagger.v3.oas.annotations.Operation;
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

/**
 * Settings · Integrations (docs/integrations.md): third-party keys and addresses, SUPER_ADMIN only. Secrets are only ever shown masked;
 * a field with nothing saved uses the server's environment value.
 */
@RestController
@RequestMapping("/admin/integrations")
@RequiredArgsConstructor
public class AdminIntegrationsController {

    private final IntegrationSettingsService integrations;
    private final IntegrationTestClient tests;

    @Operation(summary = "SUPER_ADMIN: every provider's settings card (secrets masked; null = the server's value applies)")
    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<List<IntegrationView>> list() {
        return ApiResponse.ok(integrations.list());
    }

    @Operation(summary = "SUPER_ADMIN: save fields (blank keeps the saved value) and clear fields (the server's value applies again)")
    @PutMapping("/{provider}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<IntegrationView> update(@PathVariable String provider, @RequestBody(required = false) IntegrationUpdate body) {
        return ApiResponse.ok("Saved. Services use it within a minute", integrations.update(provider, body));
    }

    @Operation(summary = "SUPER_ADMIN: a safe live check of the provider with the settings in force (never moves money)")
    @PostMapping("/{provider}/test")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<IntegrationTestResult> test(@PathVariable String provider) {
        IntegrationProvider p = IntegrationSettingsService.provider(provider);
        if (!p.testable()) {
            return ApiResponse.ok(IntegrationTestResult.failed("There's no safe live check for " + p.label()
                    + ": testing it would move money. Its settings are used as saved"));
        }
        return ApiResponse.ok(tests.test(p));
    }
}

package com.hospio.tenant.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.tenant.dto.HandoverResponse;
import com.hospio.tenant.dto.OnboardingResponse;
import com.hospio.tenant.dto.OnboardingStepRequest;
import com.hospio.tenant.dto.SetupTemplateRequest;
import com.hospio.tenant.entity.SetupTemplate;
import com.hospio.tenant.service.OnboardingService;
import jakarta.validation.Valid;
import java.util.List;
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

/** Platform admin Phase 2 (J1-J18) and the owner's K3. */
@RestController
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService service;

    @GetMapping("/onboarding")
    @PreAuthorize("@access.platform()")
    public ApiResponse<List<OnboardingResponse>> inProgress() {
        return ApiResponse.ok(service.inProgress());
    }

    @GetMapping("/onboarding/{businessId}")
    @PreAuthorize("@access.platform()")
    public ApiResponse<OnboardingResponse> get(@PathVariable String businessId) {
        return ApiResponse.ok(service.forBusiness(businessId));
    }

    @PutMapping("/onboarding/{businessId}/step")
    @PreAuthorize("@access.platform()")
    public ApiResponse<OnboardingResponse> saveStep(@PathVariable String businessId, @Valid @RequestBody OnboardingStepRequest request) {
        return ApiResponse.ok("Step saved", service.saveStep(businessId, request));
    }

    @PutMapping("/onboarding/{businessId}/assign")
    @PreAuthorize("@access.platform()")
    public ApiResponse<OnboardingResponse> reassign(@PathVariable String businessId, @RequestParam String adminId) {
        return ApiResponse.ok("Reassigned", service.reassign(businessId, adminId));
    }

    @PostMapping("/onboarding/{businessId}/handover")
    @PreAuthorize("@access.platform()")
    public ApiResponse<HandoverResponse> sendHandover(@PathVariable String businessId) {
        return ApiResponse.ok("Handover sent", service.sendHandover(businessId));
    }

    @PostMapping("/handover/accept")
    @PreAuthorize("@access.owner()")
    public ApiResponse<HandoverResponse> accept() {
        return ApiResponse.ok("Welcome to Hospio360", service.accept());
    }

    @GetMapping("/setup-templates")
    public ApiResponse<List<SetupTemplate>> templates() {
        return ApiResponse.ok(service.templates());
    }

    @PostMapping("/setup-templates")
    @PreAuthorize("@access.platform()")
    public ApiResponse<SetupTemplate> createTemplate(@Valid @RequestBody SetupTemplateRequest request) {
        return ApiResponse.ok("Template saved", service.saveTemplate(null, request));
    }

    @PutMapping("/setup-templates/{id}")
    @PreAuthorize("@access.platform()")
    public ApiResponse<SetupTemplate> updateTemplate(@PathVariable String id, @Valid @RequestBody SetupTemplateRequest request) {
        return ApiResponse.ok("Template saved", service.saveTemplate(id, request));
    }
}

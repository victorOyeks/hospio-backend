package com.hospio.tenant.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.tenant.dto.BusinessResponse;
import com.hospio.tenant.dto.HandoverResponse;
import com.hospio.tenant.dto.OnboardingResponse;
import com.hospio.tenant.dto.OnboardingStepRequest;
import com.hospio.tenant.dto.SetupTemplateRequest;
import com.hospio.tenant.entity.OnboardingDraft;
import com.hospio.tenant.entity.SetupTemplate;
import com.hospio.tenant.service.OnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform admin Phase 2 (J1-J18), the owner's K3, and the owner's own sign-up wizard. */
@RestController
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService service;

    @Operation(summary = "Platform: the onboarding board, every draft or those in one status")
    @GetMapping("/onboarding")
    @PreAuthorize("@access.platform()")
    public ApiResponse<List<OnboardingResponse>> list(@RequestParam(required = false) OnboardingDraft.Status status) {
        return ApiResponse.ok(service.list(status));
    }

    @Operation(summary = "Owner: my own sign-up wizard progress")
    @GetMapping("/onboarding/mine")
    @PreAuthorize("@access.owner()")
    public ApiResponse<OnboardingResponse> mine() {
        return ApiResponse.ok(service.mine());
    }

    @Operation(summary = "Owner: save a sign-up wizard step (1..4)")
    @PutMapping("/onboarding/mine/step")
    @PreAuthorize("@access.owner()")
    public ApiResponse<OnboardingResponse> saveMyStep(@Valid @RequestBody OnboardingStepRequest request) {
        return ApiResponse.ok("Step saved", service.saveMyStep(request));
    }

    @Operation(summary = "Owner: finish the sign-up wizard; the business goes LIVE")
    @PostMapping("/onboarding/mine/complete")
    @PreAuthorize("@access.owner()")
    public ApiResponse<BusinessResponse> completeMine() {
        return ApiResponse.ok("All set", service.completeMine());
    }

    @GetMapping("/onboarding/{businessId}")
    @PreAuthorize("@access.platform()")
    public ApiResponse<OnboardingResponse> get(@PathVariable String businessId) {
        return ApiResponse.ok(service.forBusiness(businessId));
    }

    @Operation(summary = "Platform: save a wizard step's form data under its key and mark it completed; step 10 → READY_FOR_HANDOVER")
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

    @Operation(summary = "Platform: send the handover; creates the owner login (shown once) and sets HANDED_OVER")
    @PostMapping("/onboarding/{businessId}/handover")
    @PreAuthorize("@access.platform()")
    public ApiResponse<HandoverResponse> sendHandover(@PathVariable String businessId) {
        return ApiResponse.ok("Handover sent", service.sendHandover(businessId));
    }

    @Operation(summary = "Owner: accept the handover; the business goes LIVE")
    @PostMapping("/handover/accept")
    @PreAuthorize("@access.owner()")
    public ApiResponse<BusinessResponse> accept() {
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

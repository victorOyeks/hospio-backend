package com.hospio.identity.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.identity.dto.SupportAccessGrantResponse;
import com.hospio.identity.dto.SupportAccessRequest;
import com.hospio.identity.dto.SupportAccessTokenResponse;
import com.hospio.identity.entity.SupportAccessGrant;
import com.hospio.identity.service.SupportAccessService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform admin C5 · Support access (view as owner) and C7 · Why are you looking? */
@RestController
@RequestMapping("/support-access")
@RequiredArgsConstructor
public class SupportAccessController {

    private final SupportAccessService service;

    @Operation(summary = "Open support access to a business with a reason (minutes, max 240)")
    @PostMapping
    @PreAuthorize("@access.platform()")
    public ApiResponse<SupportAccessGrant> open(@Valid @RequestBody SupportAccessRequest request) {
        return ApiResponse.ok("Support access opened", service.open(request));
    }

    @Operation(summary = "A read-only owner token (OWNER:VIEW, act = admin) for a grant the caller opened; 410 once it has ended")
    @PostMapping("/{grantId}/token")
    @PreAuthorize("@access.platform()")
    public ApiResponse<SupportAccessTokenResponse> token(@PathVariable String grantId) {
        return ApiResponse.ok("Support token issued", service.token(grantId));
    }

    @Operation(summary = "Every grant on a business, expired ones included, with the admin's name")
    @GetMapping
    @PreAuthorize("@access.platform()")
    public ApiResponse<List<SupportAccessGrantResponse>> forBusiness(@RequestParam String businessId) {
        return ApiResponse.ok(service.forBusiness(businessId));
    }

    @GetMapping("/mine")
    @PreAuthorize("@access.platform()")
    public ApiResponse<List<SupportAccessGrant>> mine() {
        return ApiResponse.ok(service.mine());
    }
}

package com.hospio.identity.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.identity.dto.SupportAccessRequest;
import com.hospio.identity.entity.SupportAccessGrant;
import com.hospio.identity.service.SupportAccessService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform admin C5 · Support access (view as owner) and C7 · Why are you looking? */
@RestController
@RequestMapping("/support-access")
@RequiredArgsConstructor
public class SupportAccessController {

    private final SupportAccessService service;

    @PostMapping
    @PreAuthorize("@access.platform()")
    public ApiResponse<SupportAccessGrant> open(@Valid @RequestBody SupportAccessRequest request) {
        return ApiResponse.ok("Support access opened", service.open(request));
    }

    @GetMapping("/mine")
    @PreAuthorize("@access.platform()")
    public ApiResponse<List<SupportAccessGrant>> mine() {
        return ApiResponse.ok(service.mine());
    }
}

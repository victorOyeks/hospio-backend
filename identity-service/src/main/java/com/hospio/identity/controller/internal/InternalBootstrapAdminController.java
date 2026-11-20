package com.hospio.identity.controller.internal;

import com.hospio.common.response.ApiResponse;
import com.hospio.identity.service.BootstrapAdminService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operations only (internal API key, /internal/**): a new temporary password for the bootstrap super admin, emailed again,
 * when the first email never arrived. Works only while that account has never signed in and no other super admin exists
 * (docs/admin-portal.md, "Bootstrap super admin"). The answer is the outcome, never the password.
 */
@Hidden
@RestController
@RequestMapping("/internal/bootstrap-admin")
@RequiredArgsConstructor
public class InternalBootstrapAdminController {

    private final BootstrapAdminService bootstrap;

    @PostMapping("/reissue")
    public ApiResponse<String> reissue() {
        BootstrapAdminService.Issue issue = bootstrap.prepare(true);
        if (issue.issued()) {
            Thread.ofVirtual().name("bootstrap-admin-reissue").start(() -> bootstrap.deliver(issue));
        }
        return ApiResponse.ok(issue.outcome().name());
    }
}

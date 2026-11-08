package com.hospio.identity.controller;

import com.hospio.common.enums.Role;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.CurrentUser;
import com.hospio.identity.dto.AccessChangeRequest;
import com.hospio.identity.dto.CreateUserRequest;
import com.hospio.identity.dto.GrantRoleRequest;
import com.hospio.identity.dto.MeResponse;
import com.hospio.identity.dto.UserCreatedResponse;
import com.hospio.identity.dto.UserResponse;
import com.hospio.identity.service.UserService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.ok(userService.me(CurrentUser.require().getUserId()));
    }

    @Operation(summary = "People of a business with roles, status and invite state; platform callers pass businessId, managers see "
            + "their hotels' people. scope=platform (SUPER_ADMIN) lists the admin team instead")
    @GetMapping
    @PreAuthorize("@access.managerView()")
    public ApiResponse<List<UserResponse>> list(@RequestParam(required = false) String scope,
                                                @RequestParam(required = false) String businessId,
                                                @RequestParam(required = false) String hotelId,
                                                @RequestParam(required = false) Role role,
                                                @RequestParam(required = false) String q) {
        if ("platform".equalsIgnoreCase(scope)) {
            return ApiResponse.ok(userService.listPlatform(q));
        }
        return ApiResponse.ok(userService.list(businessId, hotelId, role, q));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("@access.managerView()")
    public ApiResponse<UserResponse> get(@PathVariable String userId) {
        return ApiResponse.ok(userService.get(userId));
    }

    @Operation(summary = "Add a person with a first role; the temporary password and invite token come back once with the deliveries "
            + "that would have carried them. A platform role needs SUPER_ADMIN; MARKETER any platform admin")
    @PostMapping
    @PreAuthorize("@access.manager()")
    public ApiResponse<UserCreatedResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.ok("Login created", userService.create(request));
    }

    @Operation(summary = "A fresh temporary password and invite link for a person")
    @PostMapping("/{userId}/resend-invite")
    @PreAuthorize("@access.manager()")
    public ApiResponse<UserCreatedResponse> resendInvite(@PathVariable String userId) {
        return ApiResponse.ok("Invite re-sent", userService.resendInvite(userId));
    }

    @PostMapping("/{userId}/roles")
    @PreAuthorize("@access.manager()")
    public ApiResponse<UserResponse> grantRole(@PathVariable String userId, @Valid @RequestBody GrantRoleRequest request) {
        return ApiResponse.ok("Role given", userService.grantRole(userId, request));
    }

    @PutMapping("/{userId}/roles/{grantId}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<UserResponse> changeAccess(@PathVariable String userId, @PathVariable String grantId,
                                                  @Valid @RequestBody AccessChangeRequest request) {
        return ApiResponse.ok("Access changed", userService.changeAccess(userId, grantId, request));
    }

    @PostMapping("/{userId}/pause-all")
    @PreAuthorize("@access.manager()")
    public ApiResponse<UserResponse> pauseAll(@PathVariable String userId, @RequestParam(defaultValue = "true") boolean paused) {
        return ApiResponse.ok(paused ? "All access paused" : "Access resumed", userService.pauseAll(userId, paused));
    }

    /** Managers, the owner and the platform; a temporary password the person must change. */
    @PostMapping("/{userId}/reset-password")
    @PreAuthorize("@access.manager()")
    public ApiResponse<UserCreatedResponse> resetPassword(@PathVariable String userId) {
        return ApiResponse.ok("Temporary password issued", userService.resetPassword(userId));
    }
}

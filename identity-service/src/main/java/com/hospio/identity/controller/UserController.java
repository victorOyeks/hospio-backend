package com.hospio.identity.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.CurrentUser;
import com.hospio.identity.dto.AccessChangeRequest;
import com.hospio.identity.dto.CreateUserRequest;
import com.hospio.identity.dto.GrantRoleRequest;
import com.hospio.identity.dto.MeResponse;
import com.hospio.identity.dto.UserCreatedResponse;
import com.hospio.identity.dto.UserResponse;
import com.hospio.identity.service.UserService;
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

    /** Everyone in the business with their roles - the Staff access and Users screens. */
    @GetMapping
    @PreAuthorize("@access.manager()")
    public ApiResponse<List<UserResponse>> list() {
        return ApiResponse.ok(userService.listForBusiness());
    }

    @GetMapping("/{userId}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<UserResponse> get(@PathVariable String userId) {
        return ApiResponse.ok(userService.get(userId));
    }

    /** Add staff or invite a user; the temporary password comes back once. */
    @PostMapping
    @PreAuthorize("@access.manager()")
    public ApiResponse<UserCreatedResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.ok("Login created", userService.create(request));
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

    @PostMapping("/{userId}/reset-password")
    @PreAuthorize("@access.manager()")
    public ApiResponse<UserCreatedResponse> resetPassword(@PathVariable String userId) {
        return ApiResponse.ok("Temporary password issued", userService.resetPassword(userId));
    }
}

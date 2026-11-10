package com.hospio.identity.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.identity.dto.StaffAccountRequests;
import com.hospio.identity.dto.UserResponse;
import com.hospio.identity.service.StaffAccountService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner portal · Staff and the manager's Staff screen: a person's details, suspend and reinstate, and a pause for a
 * number of days that ends by itself (docs/owner-portal.md). The owner acts on everyone in the business but the owner;
 * a manager on the people of their hotels except managers.
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class StaffAccountController {

    private final StaffAccountService staff;

    @Operation(summary = "Change a person's name, phone, email or job title (only the fields sent)")
    @PutMapping("/{userId}/details")
    @PreAuthorize("@access.manager()")
    public ApiResponse<UserResponse> details(@PathVariable String userId, @Valid @RequestBody StaffAccountRequests.Details request) {
        return ApiResponse.ok("Details saved", staff.updateDetails(userId, request));
    }

    @Operation(summary = "Suspend until reinstated, with a reason: the person is signed out everywhere and their hotels' managers are told")
    @PostMapping("/{userId}/suspend")
    @PreAuthorize("@access.manager()")
    public ApiResponse<UserResponse> suspend(@PathVariable String userId, @Valid @RequestBody StaffAccountRequests.Suspend request) {
        return ApiResponse.ok("Suspended", staff.suspend(userId, request));
    }

    @Operation(summary = "Pause for a number of Lagos days counting today (1-90); access comes back by itself after the last one")
    @PostMapping("/{userId}/pause")
    @PreAuthorize("@access.manager()")
    public ApiResponse<UserResponse> pause(@PathVariable String userId, @Valid @RequestBody StaffAccountRequests.Pause request) {
        return ApiResponse.ok("Paused", staff.pause(userId, request));
    }

    @Operation(summary = "Clear a person's PIN (docs/shared-devices.md): they set a new one from their profile at their next sign-in")
    @PostMapping("/{userId}/reset-pin")
    @PreAuthorize("@access.manager()")
    public ApiResponse<UserResponse> resetPin(@PathVariable String userId) {
        return ApiResponse.ok("PIN reset", staff.resetPin(userId));
    }

    @Operation(summary = "Lift a suspension or end a pause now")
    @PostMapping("/{userId}/reinstate")
    @PreAuthorize("@access.manager()")
    public ApiResponse<UserResponse> reinstate(@PathVariable String userId) {
        return ApiResponse.ok("Reinstated", staff.reinstate(userId));
    }
}

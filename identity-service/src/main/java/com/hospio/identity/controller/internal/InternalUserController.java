package com.hospio.identity.controller.internal;

import com.hospio.common.enums.Role;
import com.hospio.common.response.ApiResponse;
import com.hospio.identity.dto.CreateUserRequest;
import com.hospio.identity.dto.UserContactResponse;
import com.hospio.identity.dto.UserCreatedResponse;
import com.hospio.identity.service.UserService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cross-service user lookups and creation (tenant-service onboarding, finance's rota, messaging's
 * recipients). Service-to-service only, gated by InternalApiKeyFilter via /internal/**.
 */
@Hidden
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserService userService;

    /** Run the temporary-access expiry now (the hourly job's work); for operations and the staging QA scripts. */
    @PostMapping("/grants/expire")
    public ApiResponse<Integer> expireGrants() {
        return ApiResponse.ok("Expired roles ended", userService.endExpiredGrants());
    }

    @GetMapping("/{userId}/contact")
    public ApiResponse<UserContactResponse> contact(@PathVariable String userId) {
        return ApiResponse.ok(userService.contact(userId));
    }

    @GetMapping("/contacts")
    public ApiResponse<List<UserContactResponse>> contacts(@RequestParam List<String> userIds) {
        return ApiResponse.ok(userService.contacts(userIds));
    }

    /** {@code {"email": true|false, "phone": true|false}}: whether a login already uses them (tenant's new-owner checks). */
    @GetMapping("/taken")
    public ApiResponse<java.util.Map<String, Boolean>> taken(@RequestParam(required = false) String email,
                                                             @RequestParam(required = false) String phone) {
        return ApiResponse.ok(userService.taken(email, phone));
    }

    /** Everyone holding a role at a hotel, e.g. HOUSEKEEPING for the cleaning rota. */
    @GetMapping("/with-role")
    public ApiResponse<List<UserContactResponse>> withRole(@RequestParam String hotelId, @RequestParam Role role) {
        return ApiResponse.ok(userService.withRole(hotelId, role));
    }

    /**
     * Onboarding creates the owner and first manager through here; {@code businessId} in the body says whose.
     * platform-service creates marketer logins here too: role MARKETER, no business. The response carries the
     * temporary password, invite token and the deliveries that would have gone out.
     */
    @PostMapping
    public ApiResponse<UserCreatedResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.ok("Login created", userService.create(request));
    }

    /** A handover sent again for an owner who already has a login: new temporary password and invite. */
    @PostMapping("/{userId}/resend-invite")
    public ApiResponse<UserCreatedResponse> resendInvite(@PathVariable String userId) {
        return ApiResponse.ok("Invite re-sent", userService.resendInvite(userId));
    }
}

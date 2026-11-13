package com.hospio.identity.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.CurrentUser;
import com.hospio.identity.dto.AcceptInviteRequest;
import com.hospio.identity.dto.AuthResponse;
import com.hospio.identity.dto.ChangePasswordRequest;
import com.hospio.identity.dto.InviteResponse;
import com.hospio.identity.dto.LoginRequest;
import com.hospio.identity.dto.MeResponse;
import com.hospio.identity.dto.PhoneCodeResponse;
import com.hospio.identity.dto.PinLoginRequest;
import com.hospio.identity.dto.RefreshTokenRequest;
import com.hospio.identity.dto.SetPinRequest;
import com.hospio.identity.dto.SharedDeviceDtos;
import com.hospio.identity.dto.SignUpRequest;
import com.hospio.identity.dto.VerifyPhoneRequest;
import com.hospio.identity.service.AuthService;
import com.hospio.identity.service.InviteService;
import com.hospio.identity.service.PhoneVerificationService;
import com.hospio.identity.service.SharedDeviceService;
import com.hospio.identity.service.SessionService;
import com.hospio.identity.service.SignUpService;
import com.hospio.identity.dto.ProfileDtos;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SignUpService signUpService;
    private final PhoneVerificationService phoneVerification;
    private final InviteService invites;
    private final SharedDeviceService devices;
    private final SessionService sessions;

    public static final String DEVICE_HEADER = "X-Device-Token";

    /** Owners, managers and admins: email and password. */
    @PostMapping("/sign-in")
    public ApiResponse<AuthResponse> signIn(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok("Signed in", authService.signIn(request));
    }

    /** The code step of a sign-in with 2-step on: the token from the password step and an app or recovery code (docs/profile.md). */
    @PostMapping("/sign-in/two-step")
    public ApiResponse<AuthResponse> signInTwoStep(@Valid @RequestBody ProfileDtos.TwoStepSignIn request) {
        return ApiResponse.ok("Signed in", authService.signInWithCode(request.twoStepToken(), request.code()));
    }

    /** Unlock a shared device with a PIN: the device token in X-Device-Token, the person tapped and their PIN (docs/shared-devices.md). */
    @PostMapping("/pin-sign-in")
    public ApiResponse<AuthResponse> pinSignIn(@RequestHeader(value = DEVICE_HEADER, required = false) String deviceToken,
                                               @Valid @RequestBody PinLoginRequest request) {
        return ApiResponse.ok("Signed in", authService.signInWithPin(deviceToken, request));
    }

    @Operation(summary = "The lock screen of a shared device: its hotel, name, auto-lock and the people who can unlock it; 401 once removed")
    @GetMapping("/device")
    public ApiResponse<SharedDeviceDtos.LockScreen> device(@RequestHeader(value = DEVICE_HEADER, required = false) String deviceToken) {
        return ApiResponse.ok(devices.lockScreen(deviceToken));
    }

    @Operation(summary = "Lock a shared device: the session's refresh token ends (reason button or idle, for the activity log)")
    @PostMapping("/device/lock")
    public ApiResponse<Void> lockDevice(@RequestHeader(value = DEVICE_HEADER, required = false) String deviceToken,
                                        @RequestBody SharedDeviceDtos.Lock request) {
        authService.lockDevice(deviceToken, request.refreshToken(), request.reason());
        return ApiResponse.ok("Locked", null);
    }

    @Operation(summary = "Self-serve sign-up: creates the business and its owner, then signs the owner in")
    @PostMapping("/sign-up")
    public ApiResponse<AuthResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        return ApiResponse.ok("Welcome to Hospio360", signUpService.signUp(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.ok("Refreshed", authService.refresh(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.signOut(CurrentUser.require().getUserId());
        return ApiResponse.ok("Signed out", null);
    }

    /**
     * Change the password with the current one. Every other session ends; this one carries on with the fresh tokens that
     * come back (docs/profile.md).
     */
    @PostMapping("/change-password")
    public ApiResponse<AuthResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        String userId = CurrentUser.require().getUserId();
        String current = sessions.currentSessionId().orElse(null);
        authService.changePassword(userId, request, current);
        // After that commit, so the new access token is not older than the access-changed record.
        return ApiResponse.ok("Password changed", sessions.reissue(userId, current));
    }

    /** Set or change one's own PIN with the password (6 digits, not trivial, not the PIN before). Owners have none. */
    @PostMapping("/set-pin")
    public ApiResponse<Void> setPin(@Valid @RequestBody SetPinRequest request) {
        authService.setPin(CurrentUser.require().getUserId(), request);
        return ApiResponse.ok("PIN set", null);
    }

    @Operation(summary = "Send a 6-digit code to the signed-in person's phone: by SMS, or {\"channel\":\"WHATSAPP\"} on WhatsApp "
            + "(an SMS while the WhatsApp code template isn't approved)")
    @PostMapping("/phone/send-code")
    public ApiResponse<PhoneCodeResponse> sendPhoneCode(@RequestBody(required = false) java.util.Map<String, String> body) {
        boolean whatsApp = body != null && "WHATSAPP".equalsIgnoreCase(body.get("channel"));
        return ApiResponse.ok("Code sent", phoneVerification.sendCode(CurrentUser.require().getUserId(), whatsApp));
    }

    @Operation(summary = "Confirm the phone with the code; 400 when wrong or expired")
    @PostMapping("/phone/verify")
    public ApiResponse<MeResponse> verifyPhone(@Valid @RequestBody VerifyPhoneRequest request) {
        return ApiResponse.ok("Phone confirmed", phoneVerification.verify(CurrentUser.require().getUserId(), request.code()));
    }

    @Operation(summary = "What an invite link is for, before the person chooses a password; 404 unknown, 410 used or expired")
    @GetMapping("/invites/{token}")
    public ApiResponse<InviteResponse> invite(@PathVariable String token) {
        return ApiResponse.ok(invites.get(token));
    }

    @Operation(summary = "Accept an invite: set the password and sign in")
    @PostMapping("/accept-invite")
    public ApiResponse<AuthResponse> acceptInvite(@Valid @RequestBody AcceptInviteRequest request) {
        return ApiResponse.ok("Welcome to Hospio360", invites.accept(request));
    }
}

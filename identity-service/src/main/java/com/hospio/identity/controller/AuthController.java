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
import com.hospio.identity.dto.SignUpRequest;
import com.hospio.identity.dto.VerifyPhoneRequest;
import com.hospio.identity.service.AuthService;
import com.hospio.identity.service.InviteService;
import com.hospio.identity.service.PhoneVerificationService;
import com.hospio.identity.service.SignUpService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /** Owners, managers and admins: email and password. */
    @PostMapping("/sign-in")
    public ApiResponse<AuthResponse> signIn(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok("Signed in", authService.signIn(request));
    }

    /** Staff on the shared tablet: phone or email plus PIN. */
    @PostMapping("/pin-sign-in")
    public ApiResponse<AuthResponse> pinSignIn(@Valid @RequestBody PinLoginRequest request) {
        return ApiResponse.ok("Signed in", authService.signInWithPin(request));
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

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(CurrentUser.require().getUserId(), request);
        return ApiResponse.ok("Password changed. Sign in again", null);
    }

    /** First sign-in on the tablet: choose a PIN. Also used by Me → Change PIN. */
    @PostMapping("/set-pin")
    public ApiResponse<Void> setPin(@Valid @RequestBody SetPinRequest request) {
        authService.setPin(CurrentUser.require().getUserId(), request);
        return ApiResponse.ok("PIN set", null);
    }

    @Operation(summary = "Send a 6-digit code to the signed-in person's phone (logged; no SMS yet)")
    @PostMapping("/phone/send-code")
    public ApiResponse<PhoneCodeResponse> sendPhoneCode() {
        return ApiResponse.ok("Code sent", phoneVerification.sendCode(CurrentUser.require().getUserId()));
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

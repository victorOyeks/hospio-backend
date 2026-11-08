package com.hospio.identity.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.CurrentUser;
import com.hospio.identity.dto.AuthResponse;
import com.hospio.identity.dto.ChangePasswordRequest;
import com.hospio.identity.dto.LoginRequest;
import com.hospio.identity.dto.PinLoginRequest;
import com.hospio.identity.dto.RefreshTokenRequest;
import com.hospio.identity.dto.SetPinRequest;
import com.hospio.identity.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

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
}

package com.hospio.identity.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.CurrentUser;
import com.hospio.identity.dto.MeResponse;
import com.hospio.identity.dto.ProfileDtos;
import com.hospio.identity.service.ProfileService;
import com.hospio.identity.service.SessionService;
import com.hospio.identity.service.TwoStepService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * My profile & security (docs/profile.md). Every route acts on the caller's own account: the user id comes from the access
 * token, never from the path. The one exception is reading a colleague's photo.
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profile;
    private final SessionService sessions;
    private final TwoStepService twoStep;

    private static String me() {
        return CurrentUser.require().getUserId();
    }

    @Operation(summary = "Change my full name, phone or language (only what is sent); 409 when the phone is someone else's")
    @PutMapping("/me")
    public ApiResponse<MeResponse> update(@Valid @RequestBody ProfileDtos.UpdateProfile request) {
        return ApiResponse.ok("Profile saved", profile.update(me(), request));
    }

    @Operation(summary = "Upload or replace my photo (JPEG or PNG, at most 2 MB; cropped square, 256 px)")
    @PostMapping(value = "/me/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MeResponse> uploadPhoto(@RequestPart(name = "file", required = false) MultipartFile file) {
        return ApiResponse.ok("Photo saved", profile.uploadPhoto(me(), file));
    }

    @DeleteMapping("/me/photo")
    public ApiResponse<MeResponse> removePhoto() {
        return ApiResponse.ok("Photo removed", profile.removePhoto(me()));
    }

    @Operation(summary = "A person's photo: themselves, the same business or the platform; 404 without one")
    @GetMapping("/{userId}/photo")
    public ResponseEntity<byte[]> photo(@PathVariable String userId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore().cachePrivate())
                .body(profile.photo(CurrentUser.require(), userId));
    }

    @Operation(summary = "Where I'm signed in: one row per session, this one marked current")
    @GetMapping("/me/sessions")
    public ApiResponse<List<ProfileDtos.Session>> sessions() {
        return ApiResponse.ok(sessions.list(me()));
    }

    @Operation(summary = "End one of my other sessions: its refresh fails and its access token is refused (docs/profile.md)")
    @DeleteMapping("/me/sessions/{sessionId}")
    public ApiResponse<Void> endSession(@PathVariable String sessionId) {
        sessions.endOne(me(), sessionId);
        return ApiResponse.ok("Signed out", null);
    }

    @Operation(summary = "End every other session; fresh tokens for this one come back")
    @PostMapping("/me/sessions/sign-out-others")
    public ApiResponse<ProfileDtos.SessionsEnded> signOutOthers() {
        String userId = me();
        String current = sessions.currentSessionId().orElse(null);
        int ended = sessions.endOthers(userId, current, "signed out other devices");
        // After that commit, so the new access token is not older than the access-changed record.
        return ApiResponse.ok("Other devices signed out", new ProfileDtos.SessionsEnded(ended, sessions.reissue(userId, current)));
    }

    @GetMapping("/me/two-step")
    public ApiResponse<ProfileDtos.TwoStepStatus> twoStep() {
        return ApiResponse.ok(twoStep.status(me()));
    }

    @Operation(summary = "Start 2-step setup: a new secret with its QR code; not on until a code confirms it")
    @PostMapping("/me/two-step/setup")
    public ApiResponse<ProfileDtos.TwoStepSetup> setup() {
        return ApiResponse.ok(twoStep.setup(me()));
    }

    @Operation(summary = "Turn 2-step on with the first code; 10 recovery codes come back once")
    @PostMapping("/me/two-step/enable")
    public ApiResponse<ProfileDtos.RecoveryCodes> enable(@Valid @RequestBody ProfileDtos.Code request) {
        return ApiResponse.ok("2-step sign-in is on", twoStep.enable(me(), request.code()));
    }

    @Operation(summary = "Turn 2-step off with the password and an app or recovery code")
    @PostMapping("/me/two-step/disable")
    public ApiResponse<ProfileDtos.TwoStepStatus> disable(@Valid @RequestBody ProfileDtos.Disable request) {
        return ApiResponse.ok("2-step sign-in is off", twoStep.disable(me(), request.password(), request.code()));
    }

    @Operation(summary = "New recovery codes with a current code; the old ones stop working")
    @PostMapping("/me/two-step/recovery-codes")
    public ApiResponse<ProfileDtos.RecoveryCodes> recoveryCodes(@Valid @RequestBody ProfileDtos.Code request) {
        return ApiResponse.ok("New codes made", twoStep.regenerate(me(), request.code()));
    }

    @GetMapping("/me/notification-preferences")
    public ApiResponse<ProfileDtos.NotificationPreferences> preferences() {
        return ApiResponse.ok(profile.preferences(me()));
    }

    @PutMapping("/me/notification-preferences")
    public ApiResponse<ProfileDtos.NotificationPreferences> updatePreferences(@RequestBody ProfileDtos.UpdatePreferences request) {
        return ApiResponse.ok("Notification settings saved", profile.updatePreferences(me(), request));
    }
}

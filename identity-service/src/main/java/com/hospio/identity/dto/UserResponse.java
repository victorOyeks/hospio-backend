package com.hospio.identity.dto;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.identity.entity.UserAccount;
import java.time.Instant;
import java.util.List;

/**
 * A person as the Users, Staff and Roles screens list them. {@code pausedUntil} is when a timed pause ends by
 * itself (null while it lasts until someone resumes it); {@code statusReason}, {@code statusChangedBy} and
 * {@code statusChangedAt} say why, who and when the account was suspended or paused.
 */
public record UserResponse(String id, String fullName, String email, String phone, String businessId, String status,
                           boolean mustChangePassword, boolean twoStepEnabled, boolean hasPin, boolean phoneVerified, String language,
                           Instant lastSignInAt, Instant createdAt, List<Grant> grants, InviteStatus inviteStatus,
                           Instant pausedUntil, String statusReason, String statusChangedBy, Instant statusChangedAt, String jobTitle,
                           Long photoVersion) {

    public UserResponse(String id, String fullName, String email, String phone, String businessId, String status,
                        boolean mustChangePassword, boolean twoStepEnabled, boolean hasPin, boolean phoneVerified, String language,
                        Instant lastSignInAt, Instant createdAt, List<Grant> grants, InviteStatus inviteStatus) {
        this(id, fullName, email, phone, businessId, status, mustChangePassword, twoStepEnabled, hasPin, phoneVerified, language,
                lastSignInAt, createdAt, grants, inviteStatus, null, null, null, null, null, null);
    }

    /** The account's pause, suspension and job title; a pause whose end has passed reads as active. */
    public UserResponse withAccount(UserAccount user) {
        boolean ended = user.pauseHasEnded(Instant.now());
        boolean held = !ended && user.getStatus() != UserAccount.Status.ACTIVE;
        return new UserResponse(id, fullName, email, phone, businessId, ended ? UserAccount.Status.ACTIVE.name() : status,
                mustChangePassword, twoStepEnabled, hasPin, phoneVerified, language, lastSignInAt, createdAt, grants, inviteStatus,
                held ? user.getPausedUntil() : null, held ? user.getStatusReason() : null, held ? user.getStatusChangedBy() : null,
                held ? user.getStatusChangedAt() : null, user.getJobTitle(), user.getPhotoVersion());
    }

    /** {@code until} is the last day a temporary role works (Lagos), from {@code expiresAt}; null when it has no end. */
    public record Grant(String id, Role role, AccessLevel level, String hotelId, boolean paused, Instant expiresAt,
                        java.time.LocalDate until, String grantedBy) {
    }

    /** The latest invite: waiting to be accepted, accepted, run out, or never issued. */
    public enum InviteStatus { PENDING, ACCEPTED, EXPIRED, NONE }
}

package com.hospio.identity.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.UnauthorizedException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.UserSignedInEvent;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.identity.dto.AuthResponse;
import com.hospio.identity.dto.ChangePasswordRequest;
import com.hospio.identity.dto.LoginRequest;
import com.hospio.identity.dto.PinLoginRequest;
import com.hospio.identity.dto.RefreshTokenRequest;
import com.hospio.identity.dto.SetPinRequest;
import com.hospio.identity.entity.RefreshToken;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RefreshTokenRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-in with email and password (owners, managers, admins) or with a PIN (staff on the shared
 * tablet), refresh, sign-out, and the password and PIN changes. Sign-in runs before any tenant is
 * known, so the lookups here are done as the platform.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final EventPublisher events;

    @Value("${hospio.auth.pin-max-attempts:5}")
    private int pinMaxAttempts;

    @Value("${hospio.auth.pin-lock-minutes:15}")
    private long pinLockMinutes;

    @Value("${hospio.auth.sign-in-max-attempts:5}")
    private int signInMaxAttempts;

    @Value("${hospio.auth.sign-in-lock-minutes:15}")
    private long signInLockMinutes;

    private static String lockedMessage(Instant until, Instant now) {
        long minutes = Math.max(1, (long) Math.ceil(java.time.Duration.between(now, until).getSeconds() / 60.0));
        return "Too many wrong passwords. Sign-in is locked for " + minutes + (minutes == 1 ? " minute" : " minutes")
                + ". Try again then, or reset your password";
    }

    /** Counted failures must survive the refusal, so these refusals don't roll the transaction back. */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public AuthResponse signIn(LoginRequest request) {
        return TenantContext.runWith(Tenant.system("sign-in"), () -> {
            UserAccount user = users.findByEmailIgnoreCase(request.email())
                    .orElseThrow(() -> new UnauthorizedException("Email or password is wrong"));
            Instant now = Instant.now();
            if (user.getSignInLockedUntil() != null && user.getSignInLockedUntil().isAfter(now)) {
                throw new UnauthorizedException(lockedMessage(user.getSignInLockedUntil(), now));
            }
            if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
                int failures = user.getFailedSignInAttempts() + 1;
                if (failures >= signInMaxAttempts) {
                    user.setFailedSignInAttempts(0);
                    user.setSignInLockedUntil(now.plus(signInLockMinutes, ChronoUnit.MINUTES));
                    users.save(user);
                    throw new UnauthorizedException(lockedMessage(user.getSignInLockedUntil(), now));
                }
                user.setFailedSignInAttempts(failures);
                users.save(user);
                int left = signInMaxAttempts - failures;
                throw new UnauthorizedException(left <= 2
                        ? "Email or password is wrong. " + left + (left == 1 ? " try" : " tries") + " left before sign-in is locked for "
                                + signInLockMinutes + " minutes"
                        : "Email or password is wrong");
            }
            if (user.getFailedSignInAttempts() > 0 || user.getSignInLockedUntil() != null) {
                user.setFailedSignInAttempts(0);
                user.setSignInLockedUntil(null);
                users.save(user);
            }
            if (user.getTemporaryPasswordExpiresAt() != null && user.getTemporaryPasswordExpiresAt().isBefore(Instant.now())) {
                throw new UnauthorizedException("Your temporary password has expired. Ask your manager for a new one");
            }
            assertActive(user);
            return finishSignIn(user, "password");
        });
    }

    @Transactional(noRollbackFor = UnauthorizedException.class)
    public AuthResponse signInWithPin(PinLoginRequest request) {
        return TenantContext.runWith(Tenant.system("pin-sign-in"), () -> {
            UserAccount user = users.findByPhone(request.identifier())
                    .or(() -> users.findByEmailIgnoreCase(request.identifier()))
                    .orElseThrow(() -> new UnauthorizedException("We don't know that phone or email"));
            if (user.isLocked()) {
                throw new UnauthorizedException("Too many wrong PINs. Try again in a few minutes or ask your manager");
            }
            if (user.getPinHash() == null) {
                throw new BadRequestException("No PIN set yet. Sign in with your password first and set one");
            }
            if (!passwordEncoder.matches(request.pin(), user.getPinHash())) {
                user.setFailedPinAttempts(user.getFailedPinAttempts() + 1);
                if (user.getFailedPinAttempts() >= pinMaxAttempts) {
                    user.setLockedUntil(Instant.now().plus(pinLockMinutes, ChronoUnit.MINUTES));
                    user.setFailedPinAttempts(0);
                }
                users.save(user);
                throw new UnauthorizedException("Wrong PIN");
            }
            user.setFailedPinAttempts(0);
            user.setLockedUntil(null);
            assertActive(user);
            return finishSignIn(user, "pin");
        });
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        return TenantContext.runWith(Tenant.system("refresh"), () -> {
            RefreshToken token = refreshTokens.findByTokenHash(Hashing.sha256(request.refreshToken()))
                    .orElseThrow(() -> new UnauthorizedException("Session expired. Sign in again"));
            if (!token.isUsable()) {
                throw new UnauthorizedException("Session expired. Sign in again");
            }
            token.setRevokedAt(Instant.now());
            refreshTokens.save(token);
            UserAccount user = users.findById(token.getUserId()).orElseThrow(() -> new UnauthorizedException("Account not found"));
            assertActive(user);
            return tokenIssuer.issue(user);
        });
    }

    @Transactional
    public void signOut(String userId) {
        TenantContext.runWith(Tenant.system("sign-out"), () -> {
            Instant now = Instant.now();
            refreshTokens.findByUserIdAndRevokedAtIsNull(userId).forEach(t -> {
                t.setRevokedAt(now);
                refreshTokens.save(t);
            });
        });
    }

    @Transactional
    public void changePassword(String userId, ChangePasswordRequest request) {
        TenantContext.runWith(Tenant.system("change-password"), () -> {
            UserAccount user = users.findById(userId).orElseThrow(() -> new UnauthorizedException("Account not found"));
            if (user.getPasswordHash() != null && !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
                throw new BadRequestException("Current password is wrong");
            }
            user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
            user.setMustChangePassword(false);
            user.setTemporaryPasswordExpiresAt(null);
            users.save(user);
            signOut(userId);
        });
    }

    @Transactional
    public void setPin(String userId, SetPinRequest request) {
        TenantContext.runWith(Tenant.system("set-pin"), () -> {
            UserAccount user = users.findById(userId).orElseThrow(() -> new UnauthorizedException("Account not found"));
            user.setPinHash(passwordEncoder.encode(request.pin()));
            user.setFailedPinAttempts(0);
            user.setLockedUntil(null);
            users.save(user);
        });
    }

    private void assertActive(UserAccount user) {
        if (user.pauseHasEnded(Instant.now())) {
            // A timed pause that has run out: active again now, before the hourly job gets to it.
            StaffAccountService.markActive(user);
            users.save(user);
            return;
        }
        if (user.getStatus() == UserAccount.Status.PAUSED && user.getPausedUntil() != null) {
            throw new ForbiddenException("This account is paused until " + AccessDates.day(AccessDates.lastDay(user.getPausedUntil()))
                    + ". Talk to your manager");
        }
        if (user.getStatus() != UserAccount.Status.ACTIVE) {
            throw new ForbiddenException("This account is " + user.getStatus().name().toLowerCase() + ". Talk to your manager");
        }
    }

    private AuthResponse finishSignIn(UserAccount user, String method) {
        user.setLastSignInAt(Instant.now());
        users.save(user);
        AuthResponse response = tokenIssuer.issue(user);
        events.publish(KafkaTopics.USER_SIGNED_IN, UserSignedInEvent.builder()
                .businessId(user.getBusinessId()).actorId(user.getId()).actorName(user.getFullName())
                .userId(user.getId()).method(method).build());
        return response;
    }
}

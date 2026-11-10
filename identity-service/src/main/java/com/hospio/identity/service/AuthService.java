package com.hospio.identity.service;

import com.hospio.common.enums.Role;
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
import com.hospio.identity.entity.SharedDevice;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RefreshTokenRepository;
import com.hospio.identity.repository.RoleGrantRepository;
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
 * Sign-in with email and password (everyone) or with a PIN on a shared device set up at the person's hotel
 * (docs/shared-devices.md), refresh, sign-out, device lock, and the password and PIN changes. Sign-in runs before any tenant is
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
    private final RoleGrantRepository grants;
    private final SharedDeviceService devices;
    private final TwoStepService twoStep;
    private final SessionService sessions;

    /** How long the code step waits after the right password. */
    static final long TWO_STEP_CHALLENGE_MINUTES = 5;

    /** A first PIN may be set without the password this long after signing in with it (docs/shared-devices.md). */
    static final long FIRST_PIN_WINDOW_MINUTES = 15;

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
            // With 2-step on, the counter is only cleared once the code is right too: a right password must not reset the
            // count of wrong codes (docs/profile.md).
            if (!user.isTwoStepEnabled() && (user.getFailedSignInAttempts() > 0 || user.getSignInLockedUntil() != null)) {
                user.setFailedSignInAttempts(0);
                user.setSignInLockedUntil(null);
                users.save(user);
            }
            if (user.getTemporaryPasswordExpiresAt() != null && user.getTemporaryPasswordExpiresAt().isBefore(Instant.now())) {
                throw new UnauthorizedException("Your temporary password has expired. Ask your manager for a new one");
            }
            assertActive(user);
            if (user.isTwoStepEnabled()) {
                String challenge = Hashing.randomToken();
                user.setTwoStepChallengeHash(Hashing.sha256(challenge));
                user.setTwoStepChallengeExpiresAt(now.plus(TWO_STEP_CHALLENGE_MINUTES, ChronoUnit.MINUTES));
                users.save(user);
                return AuthResponse.codeNeeded(challenge);
            }
            return finishSignIn(user, "password");
        });
    }

    /**
     * The code step of a sign-in with 2-step on: the token from the password step and a code from the authenticator app or a
     * recovery code. Wrong codes count with wrong passwords; the fifth locks sign-in. The token works until it runs out or a
     * new password sign-in replaces it, and is used up by the right code.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public AuthResponse signInWithCode(String twoStepToken, String code) {
        return TenantContext.runWith(Tenant.system("sign-in-two-step"), () -> {
            UserAccount user = users.findByTwoStepChallengeHash(Hashing.sha256(twoStepToken))
                    .orElseThrow(() -> new UnauthorizedException("This sign-in has expired. Enter your email and password again"));
            Instant now = Instant.now();
            if (user.getTwoStepChallengeExpiresAt() == null || user.getTwoStepChallengeExpiresAt().isBefore(now) || !user.isTwoStepEnabled()) {
                throw new UnauthorizedException("This sign-in has expired. Enter your email and password again");
            }
            if (user.getSignInLockedUntil() != null && user.getSignInLockedUntil().isAfter(now)) {
                throw new UnauthorizedException(lockedMessage(user.getSignInLockedUntil(), now));
            }
            if (!twoStep.accept(user, code, true)) {
                String message = twoStep.wrong(user, "That code is wrong");
                if (user.getSignInLockedUntil() != null && user.getSignInLockedUntil().isAfter(now)) {
                    // Locked: this sign-in is over; the next one starts with the password again.
                    user.setTwoStepChallengeHash(null);
                    user.setTwoStepChallengeExpiresAt(null);
                    users.save(user);
                }
                throw new UnauthorizedException(message);
            }
            TwoStepService.clearFailures(user);
            user.setTwoStepChallengeHash(null);
            user.setTwoStepChallengeExpiresAt(null);
            users.save(user);
            assertActive(user);
            return finishSignIn(user, "password and 2-step code");
        });
    }

    /**
     * Unlock a shared device with a PIN (docs/shared-devices.md). Only on a device set up at the person's hotel, for a role they
     * hold there; the session that comes back carries that hotel and their roles at it. Wrong PINs count against the person, not
     * the device: the fifth locks their PIN for {@code pinLockMinutes} and tells their managers. Counted failures must survive
     * the refusal, so these refusals don't roll the transaction back.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public AuthResponse signInWithPin(String deviceToken, PinLoginRequest request) {
        return TenantContext.runWith(Tenant.system("pin-sign-in"), () -> {
            SharedDevice device = devices.requireDevice(deviceToken);
            String id = request.identifier().trim();
            UserAccount user = users.findById(id)
                    .or(() -> users.findByPhone(id))
                    .or(() -> users.findByEmailIgnoreCase(id))
                    .filter(u -> device.getBusinessId().equals(u.getBusinessId()))
                    .orElseThrow(() -> new UnauthorizedException("We don't know that person at this hotel"));
            if (!pinAllowed(user)) {
                throw new ForbiddenException("Owners sign in with email and password, not a PIN");
            }
            if (!devices.fitsDevice(user.getId(), device)) {
                throw new ForbiddenException(user.getFullName() + " doesn't work on " + device.getName() + ". Sign in with email instead");
            }
            Instant now = Instant.now();
            if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
                throw new UnauthorizedException(pinLockedMessage(user.getLockedUntil(), now));
            }
            if (user.getPinHash() == null) {
                throw new BadRequestException("No PIN yet. Sign in with email and set one when asked");
            }
            if (!passwordEncoder.matches(request.pin(), user.getPinHash())) {
                int failures = user.getFailedPinAttempts() + 1;
                if (failures >= pinMaxAttempts) {
                    user.setFailedPinAttempts(0);
                    user.setLockedUntil(now.plus(pinLockMinutes, ChronoUnit.MINUTES));
                    users.save(user);
                    devices.audit(device, "PIN_LOCKED", user, pinMaxAttempts + " wrong tries, locked for " + pinLockMinutes + " minutes");
                    devices.tellManagers(device, user.getFullName() + "'s PIN is locked",
                            pinMaxAttempts + " wrong PINs on " + device.getName() + ". Their PIN works again in " + pinLockMinutes
                                    + " minutes, or reset it from Staff");
                    throw new UnauthorizedException("Wrong PIN. Your PIN is now locked for " + pinLockMinutes
                            + " minutes. Sign in with email instead, or ask your manager");
                }
                user.setFailedPinAttempts(failures);
                users.save(user);
                int left = pinMaxAttempts - failures;
                devices.audit(device, "PIN_FAILED", user, failures + " of " + pinMaxAttempts);
                throw new UnauthorizedException("Wrong PIN. " + left + (left == 1 ? " try" : " tries") + " left before your PIN is locked for "
                        + pinLockMinutes + " minutes");
            }
            if (user.getFailedPinAttempts() > 0 || user.getLockedUntil() != null) {
                user.setFailedPinAttempts(0);
                user.setLockedUntil(null);
                users.save(user);
            }
            assertActive(user);
            user.setLastSignInAt(now);
            users.save(user);
            devices.used(device, user);
            AuthResponse response = tokenIssuer.issueOnDevice(user, device.getHotelId(), device.getId());
            events.publish(KafkaTopics.USER_SIGNED_IN, UserSignedInEvent.builder()
                    .businessId(user.getBusinessId()).hotelId(device.getHotelId()).actorId(user.getId()).actorName(user.getFullName())
                    .userId(user.getId()).method("PIN on " + device.getName()).build());
            return response;
        });
    }

    /** Lock the device: the session's refresh token ends (the web drops its cookies and shows the lock screen). */
    @Transactional
    public void lockDevice(String deviceToken, String refreshToken, String reason) {
        TenantContext.runWith(Tenant.system("device-lock"), () -> {
            SharedDevice device = devices.findDevice(deviceToken).orElse(null);
            if (refreshToken == null || refreshToken.isBlank()) {
                return;
            }
            refreshTokens.findByTokenHash(Hashing.sha256(refreshToken)).ifPresent(token -> {
                boolean wasOpen = token.getRevokedAt() == null;
                if (wasOpen) {
                    token.setRevokedAt(Instant.now());
                    token.setEndedAt(Instant.now());
                    refreshTokens.save(token);
                }
                if (wasOpen && device != null && !device.isRevoked()) {
                    users.findById(token.getUserId()).ifPresent(user -> devices.audit(device, "LOCKED", user,
                            "idle".equalsIgnoreCase(reason) ? "idle " + device.getAutoLockMinutes() + " min" : "switch person"));
                }
            });
        });
    }

    private static String pinLockedMessage(Instant until, Instant now) {
        long minutes = Math.max(1, (long) Math.ceil(java.time.Duration.between(now, until).getSeconds() / 60.0));
        return "Your PIN is locked for " + minutes + (minutes == 1 ? " more minute" : " more minutes")
                + " after too many wrong tries. Sign in with email instead, or ask your manager";
    }

    /** No PINs for the owner or the platform: they sign in with email and password. */
    boolean pinAllowed(UserAccount user) {
        return !user.isPlatformAccount() && grants.findByUserId(user.getId()).stream().noneMatch(g -> g.getRole() == Role.OWNER);
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        return TenantContext.runWith(Tenant.system("refresh"), () -> {
            RefreshToken token = refreshTokens.findByTokenHash(Hashing.sha256(request.refreshToken()))
                    .orElseThrow(() -> new UnauthorizedException("Session expired. Sign in again"));
            if (!token.isUsable()) {
                throw new UnauthorizedException("Session expired. Sign in again");
            }
            // Take turns with a sign-out of this person, then look again: a session signed out meanwhile stays out.
            UserAccount user = users.lockById(token.getUserId()).orElseThrow(() -> new UnauthorizedException("Account not found"));
            if (token.getSessionId() != null && refreshTokens.sessionEnded(token.getSessionId())) {
                throw new UnauthorizedException("Session expired. Sign in again");
            }
            token.setRevokedAt(Instant.now());
            refreshTokens.save(token);
            assertActive(user);
            if (token.getDeviceId() != null) {
                // A PIN session: only while the device is still set up and the person still has a role that fits it.
                SharedDevice device = devices.findById(token.getDeviceId()).filter(d -> !d.isRevoked())
                        .orElseThrow(() -> new UnauthorizedException("This device is no longer a shared device. Sign in with email"));
                if (!devices.fitsDevice(user.getId(), device)) {
                    throw new UnauthorizedException("You no longer have a role on " + device.getName() + ". Ask your manager");
                }
                return tokenIssuer.continueSession(user, token, device.getHotelId());
            }
            return tokenIssuer.continueSession(user, token, null);
        });
    }

    @Transactional
    public void signOut(String userId) {
        TenantContext.runWith(Tenant.system("sign-out"), () -> {
            Instant now = Instant.now();
            users.lockById(userId);
            refreshTokens.findByUserIdAndRevokedAtIsNull(userId).forEach(t -> {
                t.setRevokedAt(now);
                t.setEndedAt(now);
                refreshTokens.save(t);
            });
        });
    }

    /**
     * Changes the password with the current one and ends every other session of the person ({@code keepSessionId}, the
     * caller's own, stays; the controller then issues it fresh tokens). docs/profile.md.
     */
    @Transactional
    public void changePassword(String userId, ChangePasswordRequest request, String keepSessionId) {
        TenantContext.runWith(Tenant.system("change-password"), () -> {
            UserAccount user = users.findById(userId).orElseThrow(() -> new UnauthorizedException("Account not found"));
            if (user.getPasswordHash() != null && !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
                throw new BadRequestException("Current password is wrong");
            }
            user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
            user.setMustChangePassword(false);
            user.setTemporaryPasswordExpiresAt(null);
            user.setPasswordChangedAt(Instant.now());
            users.save(user);
            sessions.endOthers(userId, keepSessionId, "password changed");
        });
    }

    /**
     * Set or change one's own PIN after a normal sign-in, with the password (docs/shared-devices.md): 4-6 digits, not trivial
     * ({@link PinRules}), not the same as the PIN before, stored as a BCrypt hash. Owners and the platform have no PIN.
     */
    @Transactional
    public void setPin(String userId, SetPinRequest request) {
        // The hotel the person is working in (X-Hotel-Id), so the activity line shows under it.
        String workingHotel = TenantContext.current().map(Tenant::hotelId).orElse(null);
        TenantContext.runWith(Tenant.system("set-pin"), () -> {
            UserAccount user = users.findById(userId).orElseThrow(() -> new UnauthorizedException("Account not found"));
            if (!pinAllowed(user)) {
                throw new ForbiddenException("Owners and H.M.S staff sign in with email and password. PINs are for staff on shared devices");
            }
            boolean noPassword = request.password() == null || request.password().isBlank();
            if (noPassword) {
                // Only a first PIN, straight after a sign-in with the password (or an accepted invite).
                boolean fresh = user.getLastSignInAt() != null
                        && user.getLastSignInAt().isAfter(Instant.now().minus(FIRST_PIN_WINDOW_MINUTES, ChronoUnit.MINUTES));
                if (user.getPinHash() != null || !fresh) {
                    throw new BadRequestException("Enter your password to " + (user.getPinHash() != null ? "change your PIN" : "set a PIN"));
                }
            } else if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
                throw new BadRequestException("Your password is wrong");
            }
            PinRules.check(request.pin(), user.getPhone());
            boolean change = user.getPinHash() != null;
            if (change && passwordEncoder.matches(request.pin(), user.getPinHash())) {
                throw new BadRequestException("That's your current PIN. Pick a new one");
            }
            user.setPinHash(passwordEncoder.encode(request.pin()));
            user.setFailedPinAttempts(0);
            user.setLockedUntil(null);
            users.save(user);
            String hotel = workingHotel != null ? workingHotel : grants.findByUserId(user.getId()).stream()
                    .map(com.hospio.identity.entity.RoleGrant::getHotelId).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            devices.audit(null, hotel, "PIN_SET", user, change ? "changed" : "first PIN");
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

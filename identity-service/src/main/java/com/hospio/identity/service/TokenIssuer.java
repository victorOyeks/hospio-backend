package com.hospio.identity.service;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.security.JwtUtil;
import com.hospio.identity.client.TenantClient;
import com.hospio.identity.dto.AuthResponse;
import com.hospio.identity.entity.RefreshToken;
import com.hospio.identity.entity.RoleGrant;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RefreshTokenRepository;
import com.hospio.identity.repository.RoleGrantRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Turns an account plus its active grants into an access token and a refresh token. The token
 * carries every role with its highest level and every hotel the person is granted, so the apps can
 * offer a role switcher without another round trip. An owner's token carries every hotel of the
 * business (asked of tenant-service; the grant hotels if it is down) so they can pick a branch.
 * Platform accounts are scope {@code platform}, except marketers, who get {@code marketer}.
 * <p>
 * Hospio360 staff (a platform role) follow the admin portal's security settings ({@link AdminSecurityPolicy}, docs/sign-in.md):
 * the session lasts {@code sessionHours} from the sign-in (refresh token and access token never outlive it; a refresh past it is
 * refused), and with "Require 2-step sign-in for every admin" on, someone without 2-step gets a token limited to setting it up
 * (the {@code setup} claim), until 2-step is on and the session is renewed.
 */
@Service
@RequiredArgsConstructor
public class TokenIssuer {

    private final JwtUtil jwtUtil;
    private final RoleGrantRepository roleGrants;
    private final RefreshTokenRepository refreshTokens;
    private final TenantClient tenantClient;
    private final AdminSecurityPolicy adminSecurity;

    @Value("${hospio.auth.refresh-days:30}")
    private long refreshDays;

    /** A new session (a sign-in, accepted invite or sign-up) for everything the person holds. */
    public AuthResponse issue(UserAccount user) {
        return issue(user, null, null, null, null);
    }

    /**
     * A new session in an existing browser family: the session the other web app opens with a hand-over code joins the family of
     * the session that asked for it, so "Sign out" in either app ends both (docs/sign-in.md).
     */
    public AuthResponse issueInFamily(UserAccount user, String familyId) {
        return issue(user, null, null, null, familyId);
    }

    /** "Your session has ended. Sign in again": a staff session past its session length. */
    public static final String SESSION_ENDED = "Your session has ended. Sign in again";

    /**
     * The next tokens of an existing session (a refresh, or the caller's own session after a password change): the same
     * session id, device and the browser it was opened from (docs/profile.md).
     */
    public AuthResponse continueSession(UserAccount user, RefreshToken previous, String onlyHotel) {
        return issue(user, onlyHotel, previous.getDeviceId(), previous, previous.family());
    }

    /**
     * A session opened with a PIN on a shared device: only the person's roles at the device's hotel, that hotel alone, and a
     * refresh token that names the device (docs/shared-devices.md). Roles and levels are otherwise exactly as for a sign-in.
     */
    public AuthResponse issueOnDevice(UserAccount user, String hotelId, String deviceId) {
        return issue(user, hotelId, deviceId, null, null);
    }

    /** The person's active roles at one hotel (or everywhere when {@code hotelId} is null), highest level per role. */
    public Map<Role, AccessLevel> rolesAt(String userId, String hotelId) {
        Map<Role, AccessLevel> roles = new EnumMap<>(Role.class);
        for (RoleGrant g : roleGrants.findByUserId(userId)) {
            if (g.isActive() && (hotelId == null || hotelId.equals(g.getHotelId()))) {
                roles.merge(g.getRole(), g.getLevel(), (a, b) -> a.atLeast(b) ? a : b);
            }
        }
        return roles;
    }

    private AuthResponse issue(UserAccount user, String onlyHotel, String deviceId, RefreshToken previous, String familyId) {
        List<RoleGrant> grants = roleGrants.findByUserId(user.getId()).stream().filter(RoleGrant::isActive)
                .filter(g -> onlyHotel == null || onlyHotel.equals(g.getHotelId())).toList();
        Map<Role, AccessLevel> roles = new EnumMap<>(Role.class);
        Set<String> hotels = new LinkedHashSet<>();
        for (RoleGrant g : grants) {
            roles.merge(g.getRole(), g.getLevel(), (a, b) -> a.atLeast(b) ? a : b);
            if (g.getHotelId() != null) {
                hotels.add(g.getHotelId());
            }
        }
        String scope = scopeFor(user, roles);
        if (onlyHotel == null && JwtUtil.SCOPE_BUSINESS.equals(scope)) {
            tenantClient.hotelIds(user.getBusinessId()).ifPresent(hotels::addAll);
        }
        String sessionId = previous != null && previous.getSessionId() != null ? previous.getSessionId() : UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant signedInAt = previous != null && previous.getSignedInAt() != null ? previous.getSignedInAt() : now;
        Instant refreshUntil = now.plus(refreshDays, ChronoUnit.DAYS);
        long accessMs = jwtUtil.getExpirationMs();
        boolean setupOnly = false;
        java.util.OptionalInt staffHours = deviceId == null ? adminSecurity.sessionHours(roles.keySet()) : java.util.OptionalInt.empty();
        if (staffHours.isPresent()) {
            // A staff session lasts sessionHours from its sign-in. A session opened by a hand-over keeps the start of the family it
            // joins, so hopping between the apps never lengthens it.
            if (previous == null && familyId != null) {
                signedInAt = familyStart(user.getId(), familyId).orElse(now);
            }
            Instant sessionEnd = signedInAt.plus(staffHours.getAsInt(), ChronoUnit.HOURS);
            if (!sessionEnd.isAfter(now)) {
                throw new com.hospio.common.exception.UnauthorizedException(SESSION_ENDED);
            }
            if (sessionEnd.isBefore(refreshUntil)) {
                refreshUntil = sessionEnd;
            }
            accessMs = Math.min(accessMs, java.time.Duration.between(now, sessionEnd).toMillis());
            setupOnly = adminSecurity.twoStepSetupOnly(roles.keySet(), user.isTwoStepEnabled());
        }
        String access = jwtUtil.generateSessionToken(user.getId(), user.getEmail(), user.getFullName(), user.getBusinessId(),
                List.copyOf(hotels), roles, scope, sessionId, accessMs, setupOnly);

        ClientInfo client = previous == null ? ClientInfo.current() : new ClientInfo(previous.getUserAgent(), previous.getLocation());
        String raw = Hashing.randomToken();
        refreshTokens.save(RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(Hashing.sha256(raw))
                .expiresAt(refreshUntil)
                .deviceId(deviceId)
                .sessionId(sessionId)
                .familyId(familyId != null ? familyId : sessionId)
                .userAgent(client.userAgent())
                .location(client.location())
                .signedInAt(signedInAt)
                .build());

        // Tokens are issued only once any 2-step code has been given, so nothing more is owed here; setupOnly says the
        // admin app must send the person to "Set up 2-step sign-in" first.
        return new AuthResponse(access, raw, Math.max(1, accessMs / 1000),
                roles.keySet().stream().map(Enum::name).toList(), user.isMustChangePassword(), false, List.of(), null, setupOnly);
    }

    /** When the browser family a hand-over joins was signed in: its earliest live session. */
    private java.util.Optional<Instant> familyStart(String userId, String familyId) {
        return refreshTokens.findByUserIdAndRevokedAtIsNull(userId).stream()
                .filter(t -> familyId.equals(t.family()) && t.getSignedInAt() != null)
                .map(RefreshToken::getSignedInAt).min(Instant::compareTo);
    }

    static String scopeFor(UserAccount user, Map<Role, AccessLevel> roles) {
        if (user.isPlatformAccount()) {
            boolean marketerOnly = roles.containsKey(Role.MARKETER) && roles.keySet().stream().noneMatch(Role::isPlatform);
            return marketerOnly ? JwtUtil.SCOPE_MARKETER : JwtUtil.SCOPE_PLATFORM;
        }
        return roles.containsKey(Role.OWNER) ? JwtUtil.SCOPE_BUSINESS : JwtUtil.SCOPE_HOTEL;
    }
}

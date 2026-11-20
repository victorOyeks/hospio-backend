package com.hospio.identity.service;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.UnauthorizedException;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.identity.dto.AuthResponse;
import com.hospio.identity.dto.HandoffDtos;
import com.hospio.identity.entity.HandoffCode;
import com.hospio.identity.entity.RefreshToken;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.HandoffCodeRepository;
import com.hospio.identity.repository.RefreshTokenRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Single sign-in across the two web apps (docs/sign-in.md). Everyone signs in at the hotel app (or the admin console); when the
 * account belongs on the other app, the app that signed them in asks for a one-time code here and sends the browser to the other
 * app's {@code /auth/handoff?code=…}, which swaps the code for its own session at once ({@link #exchange}). No cookie is shared
 * between the apps.
 *
 * <p>A code is 256 random bits, stored only as its SHA-256, bound to one person and one target app, valid for
 * {@code hospio.auth.handoff-seconds} (30 by default, never more than 60) and used once: the first exchange marks it used in the
 * database and every later one (a replay, or a race) is refused. The target apps' addresses come from configuration
 * ({@code hospio.apps.hotel-url}, {@code hospio.apps.admin-url}); the URL is never taken from the request.
 */
@Slf4j
@Service
public class HandoffService {

    public static final String HOTEL = "hotel";
    public static final String ADMIN = "admin";
    static final long MAX_SECONDS = 60;
    static final int MAX_NEXT = 512;
    /** A path of the target app: one leading slash, no scheme, no backslash, no control or space characters. */
    private static final Pattern RELATIVE_PATH = Pattern.compile("^/(?![/\\\\])[A-Za-z0-9\\-._~!$&'()*+,;=:@%/?#]*$");

    private final HandoffCodeRepository codes;
    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final TokenIssuer tokenIssuer;
    private final Map<String, String> origins;
    private final long seconds;
    Clock clock = Clock.systemUTC();

    public HandoffService(HandoffCodeRepository codes, UserAccountRepository users, RefreshTokenRepository refreshTokens,
                          TokenIssuer tokenIssuer,
                          @Value("${hospio.apps.hotel-url:}") String hotelUrl,
                          @Value("${hospio.apps.admin-url:}") String adminUrl,
                          @Value("${hospio.auth.handoff-seconds:30}") long seconds) {
        this.codes = codes;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.tokenIssuer = tokenIssuer;
        this.origins = Map.of(HOTEL, origin(hotelUrl), ADMIN, origin(adminUrl));
        this.seconds = Math.max(5, Math.min(MAX_SECONDS, seconds));
    }

    /** The scheme and host of a configured app address ("" when unset or not http(s)), so no path or query can ride along. */
    static String origin(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http")) || !StringUtils.hasText(uri.getHost())) {
                return "";
            }
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** The app an account belongs on: the admin console for platform staff and marketers, else the hotel app. */
    static boolean belongsOn(String target, Iterable<Role> roles) {
        for (Role r : roles) {
            boolean admin = r.isPlatform() || r == Role.MARKETER;
            if (r != Role.SYSTEM && (ADMIN.equals(target) == admin)) {
                return true;
            }
        }
        return false;
    }

    /** A page of the target app, or null: relative, one leading slash, nothing that could leave the app's origin. */
    static String safeNext(String next) {
        if (!StringUtils.hasText(next) || next.length() > MAX_NEXT) {
            return null;
        }
        return RELATIVE_PATH.matcher(next).matches() && !next.contains("/..") ? next : null;
    }

    private String require(String target) {
        String t = target == null ? "" : target.trim().toLowerCase(Locale.ROOT);
        String origin = origins.getOrDefault(t, "");
        if (origin.isEmpty()) {
            throw new BadRequestException("Unknown app");
        }
        return t;
    }

    /**
     * A code for the caller to open {@code target} signed in. Refused for an app the account has no role on, for a support
     * "view as owner" session and for a PIN session on a shared device (those never leave the device). {@code endSession}: the
     * caller's session (a sign-in made on the wrong app) ends now and the code does not depend on it.
     */
    @Transactional
    public HandoffDtos.Issued issue(String userId, String sessionId, String actedBy, HandoffDtos.Issue request) {
        String target = require(request.target());
        if (actedBy != null) {
            throw new ForbiddenException("A support view can't open the other app");
        }
        if (!StringUtils.hasText(sessionId)) {
            throw new UnauthorizedException("Sign in again");
        }
        boolean endSession = Boolean.TRUE.equals(request.endSession());
        return TenantContext.runWith(Tenant.system("handoff"), () -> {
            UserAccount user = users.findById(userId).orElseThrow(() -> new UnauthorizedException("Account not found"));
            if (user.getStatus() != UserAccount.Status.ACTIVE) {
                throw new ForbiddenException("This account is " + user.getStatus().name().toLowerCase(Locale.ROOT) + ". Talk to your manager");
            }
            Map<Role, AccessLevel> roles = tokenIssuer.rolesAt(userId, null);
            if (!belongsOn(target, roles.keySet())) {
                throw new ForbiddenException(ADMIN.equals(target)
                        ? "This account has no access to the Hospio360 console"
                        : "This account has no access to the business app");
            }
            List<RefreshToken> session = refreshTokens.findBySessionIdAndRevokedAtIsNull(sessionId);
            if (session.stream().anyMatch(t -> t.getDeviceId() != null)) {
                throw new ForbiddenException("A shared device stays in this app");
            }
            Instant now = clock.instant();
            String raw = Hashing.randomToken();
            codes.save(HandoffCode.builder()
                    .codeHash(Hashing.sha256(raw))
                    .userId(userId)
                    .target(target)
                    .nextPath(safeNext(request.next()))
                    .sessionId(endSession ? null : sessionId)
                    .expiresAt(now.plusSeconds(seconds))
                    .build());
            if (endSession) {
                // The sign-in on the wrong app is not kept: only its own refresh tokens end (no access-changed event, so the
                // session the code opens is not refused by a change recorded in the same second).
                for (RefreshToken t : session) {
                    t.setRevokedAt(now);
                    t.setEndedAt(now);
                    refreshTokens.save(t);
                }
            }
            log.info("Handoff code issued for user {} to {} (expires in {}s)", userId, target, seconds);
            return new HandoffDtos.Issued(origins.get(target) + "/auth/handoff?code=" + raw, target, seconds);
        });
    }

    /**
     * The target app swaps a code for its own session (a new one, with the person's current roles). Refused when unknown,
     * expired, already used, for another app, when the person has lost access to that app, is no longer active, or signed out
     * of the session the code came from. A refused code is used up too, so a probe on the wrong app can't be followed by a use
     * on the right one.
     */
    @Transactional(noRollbackFor = {UnauthorizedException.class, ForbiddenException.class})
    public HandoffDtos.Exchanged exchange(HandoffDtos.Exchange request) {
        String target = require(request.target());
        return TenantContext.runWith(Tenant.system("handoff-exchange"), () -> {
            HandoffCode code = codes.findByCodeHash(Hashing.sha256(request.code().trim()))
                    .orElseThrow(HandoffService::expired);
            Instant now = clock.instant();
            if (code.getUsedAt() != null || codes.markUsed(code.getId(), now) == 0) {
                log.warn("Handoff code for user {} presented again (replay refused)", code.getUserId());
                throw new UnauthorizedException("This sign-in link was already used. Sign in again");
            }
            if (!code.getExpiresAt().isAfter(now)) {
                throw expired();
            }
            if (!target.equals(code.getTarget())) {
                log.warn("Handoff code for user {} to {} presented on {} (refused)", code.getUserId(), code.getTarget(), target);
                throw new UnauthorizedException("This sign-in link is for the other app. Sign in again");
            }
            UserAccount user = users.lockById(code.getUserId()).orElseThrow(HandoffService::expired);
            if (user.getStatus() != UserAccount.Status.ACTIVE) {
                throw new ForbiddenException("This account is " + user.getStatus().name().toLowerCase(Locale.ROOT) + ". Talk to your manager");
            }
            if (!belongsOn(target, tokenIssuer.rolesAt(user.getId(), null).keySet())) {
                throw new ForbiddenException("This account no longer has access to this app");
            }
            if (code.getSessionId() != null && refreshTokens.sessionEnded(code.getSessionId())) {
                throw new UnauthorizedException("You signed out. Sign in again");
            }
            AuthResponse tokens = tokenIssuer.issue(user);
            log.info("Handoff code exchanged for user {} on {}", user.getId(), target);
            return new HandoffDtos.Exchanged(tokens.accessToken(), tokens.refreshToken(), tokens.expiresIn(), tokens.roles(),
                    tokens.mustChangePassword(), safeNext(code.getNextPath()));
        });
    }

    private static UnauthorizedException expired() {
        return new UnauthorizedException("This sign-in link has expired. Sign in again");
    }

    /** Nightly at 03:25 (Lagos): codes that ran out more than a day ago are deleted. */
    @Scheduled(cron = "0 25 3 * * *", zone = "Africa/Lagos")
    public void purgeExpired() {
        int deleted = purge(Duration.ofDays(1));
        if (deleted > 0) {
            log.info("Deleted {} expired handoff code(s)", deleted);
        }
    }

    int purge(Duration keep) {
        return TenantContext.runWith(Tenant.system("handoff-cleanup"), () -> codes.deleteExpired(clock.instant().minus(keep)));
    }
}

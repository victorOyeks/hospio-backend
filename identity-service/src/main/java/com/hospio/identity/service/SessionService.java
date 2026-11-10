package com.hospio.identity.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.exception.UnauthorizedException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.AccessChangedEvent;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.identity.dto.AuthResponse;
import com.hospio.identity.dto.ProfileDtos;
import com.hospio.identity.entity.RefreshToken;
import com.hospio.identity.entity.SharedDevice;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RefreshTokenRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Where a person is signed in (docs/profile.md). A session is one sign-in; its refresh tokens rotate and keep the session
 * id, which the access token carries as {@code sid}. Ending a session revokes its refresh tokens and marks them ended, then
 * an {@code AccessChangedEvent} (SESSIONS_ENDED) makes every service refuse the person's older access tokens: live sessions
 * refresh once, an ended one can't. Ending and refreshing take the person's row lock, so a refresh can't outlive a sign-out.
 * Refresh tokens are platform-only rows, so everything here runs as the platform, always for the caller's own account.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final RefreshTokenRepository refreshTokens;
    private final UserAccountRepository users;
    private final SharedDeviceService devices;
    private final TokenIssuer tokenIssuer;
    private final EventPublisher events;
    private final JwtUtil jwtUtil;

    /** The {@code sid} of the access token on this request, if it has one (tokens from before sessions have none). */
    public Optional<String> currentSessionId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return Optional.empty();
        }
        String header = attrs.getRequest().getHeader("Authorization");
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jwtUtil.extractAllClaims(header.substring(7)).get("sid", String.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public List<ProfileDtos.Session> list(String userId) {
        String current = currentSessionId().orElse(null);
        return system(() -> {
            Map<String, RefreshToken> latest = new LinkedHashMap<>();
            for (RefreshToken t : refreshTokens.findLive(userId, Instant.now())) {
                String sid = t.getSessionId() != null ? t.getSessionId() : t.getId();
                latest.merge(sid, t, (a, b) -> a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b);
            }
            return latest.entrySet().stream()
                    .map(e -> view(e.getKey(), e.getValue(), e.getKey().equals(current)))
                    .sorted(Comparator.comparing(ProfileDtos.Session::current).reversed()
                            .thenComparing(ProfileDtos.Session::lastUsedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        });
    }

    private ProfileDtos.Session view(String sid, RefreshToken t, boolean current) {
        String deviceName = t.getDeviceId() == null ? null : devices.findById(t.getDeviceId()).map(SharedDevice::getName).orElse("Shared device");
        return new ProfileDtos.Session(sid, t.getDeviceId() == null ? "PASSWORD" : "PIN", ClientInfo.describe(t.getUserAgent()),
                deviceName, t.getLocation(), t.getSignedInAt() != null ? t.getSignedInAt() : t.getCreatedAt(), t.getCreatedAt(), current);
    }

    /** Ends one of the caller's other sessions. 404 when it isn't one of theirs (or already ended); 400 for this one. */
    @Transactional
    public void endOne(String userId, String sessionId) {
        String current = currentSessionId().orElse(null);
        if (sessionId.equals(current)) {
            throw new BadRequestException("That's this device. Use Sign out instead");
        }
        system(() -> {
            UserAccount user = users.lockById(userId).orElseThrow(() -> new UnauthorizedException("Account not found"));
            List<RefreshToken> tokens = refreshTokens.findLive(userId, Instant.now()).stream()
                    .filter(t -> sessionId.equals(t.getSessionId() != null ? t.getSessionId() : t.getId())).toList();
            if (tokens.isEmpty()) {
                throw new ResourceNotFoundException("That session has already ended");
            }
            end(tokens);
            published(user, "signed out one device");
            return null;
        });
    }

    /** Ends every session of the person but {@code keepSessionId} (null: every one). How many ended. */
    @Transactional
    public int endOthers(String userId, String keepSessionId, String detail) {
        return system(() -> {
            UserAccount user = users.lockById(userId).orElseThrow(() -> new UnauthorizedException("Account not found"));
            List<RefreshToken> tokens = refreshTokens.findLive(userId, Instant.now()).stream()
                    .filter(t -> keepSessionId == null || !keepSessionId.equals(t.getSessionId())).toList();
            long sessions = tokens.stream().map(t -> t.getSessionId() != null ? t.getSessionId() : t.getId()).distinct().count();
            end(tokens);
            // Published even when nothing was live: an access token can outlive its session's refresh token.
            published(user, detail);
            return (int) sessions;
        });
    }

    /**
     * Fresh tokens for the caller's own session after the others ended (call it after that transaction committed, so the
     * new access token is not older than the access-changed record). A token without a session gets a new session.
     */
    @Transactional
    public AuthResponse reissue(String userId, String sessionId) {
        return system(() -> {
            UserAccount user = users.lockById(userId).orElseThrow(() -> new UnauthorizedException("Account not found"));
            Optional<RefreshToken> mine = sessionId == null ? Optional.empty() : refreshTokens.findLive(userId, Instant.now()).stream()
                    .filter(t -> sessionId.equals(t.getSessionId()))
                    .max(Comparator.comparing(RefreshToken::getCreatedAt));
            if (mine.isEmpty()) {
                return tokenIssuer.issue(user);
            }
            RefreshToken previous = mine.get();
            previous.setRevokedAt(Instant.now());
            refreshTokens.save(previous);
            String hotel = previous.getDeviceId() == null ? null
                    : devices.findById(previous.getDeviceId()).map(SharedDevice::getHotelId).orElse(null);
            return tokenIssuer.continueSession(user, previous, hotel);
        });
    }

    /** Revokes and marks ended (a refresh racing this sees the mark and is refused). */
    void end(List<RefreshToken> tokens) {
        Instant now = Instant.now();
        for (RefreshToken t : tokens) {
            if (t.getRevokedAt() == null) {
                t.setRevokedAt(now);
            }
            t.setEndedAt(now);
            refreshTokens.save(t);
        }
    }

    private void published(UserAccount user, String detail) {
        events.publish(KafkaTopics.ACCESS_CHANGED, AccessChangedEvent.builder()
                .businessId(user.getBusinessId()).actorId(user.getId()).actorName(user.getFullName())
                .userId(user.getId()).userName(user.getFullName()).change("SESSIONS_ENDED").detail(detail).build());
    }

    private static <T> T system(java.util.function.Supplier<T> work) {
        return TenantContext.runWith(Tenant.system("sessions"), work::get);
    }
}

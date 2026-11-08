package com.hospio.identity.service;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.security.JwtUtil;
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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Turns an account plus its active grants into an access token and a refresh token. The token
 * carries every role with its highest level and every hotel the person is granted, so the apps can
 * offer a role switcher without another round trip.
 */
@Service
@RequiredArgsConstructor
public class TokenIssuer {

    private final JwtUtil jwtUtil;
    private final RoleGrantRepository roleGrants;
    private final RefreshTokenRepository refreshTokens;

    @Value("${hospio.auth.refresh-days:30}")
    private long refreshDays;

    public AuthResponse issue(UserAccount user) {
        List<RoleGrant> grants = roleGrants.findByUserId(user.getId()).stream().filter(RoleGrant::isActive).toList();
        Map<Role, AccessLevel> roles = new EnumMap<>(Role.class);
        Set<String> hotels = new LinkedHashSet<>();
        for (RoleGrant g : grants) {
            roles.merge(g.getRole(), g.getLevel(), (a, b) -> a.atLeast(b) ? a : b);
            if (g.getHotelId() != null) {
                hotels.add(g.getHotelId());
            }
        }
        boolean platform = user.isPlatformAccount();
        String scope = platform ? JwtUtil.SCOPE_PLATFORM : (roles.containsKey(Role.OWNER) ? JwtUtil.SCOPE_BUSINESS : JwtUtil.SCOPE_HOTEL);
        String access = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getFullName(), user.getBusinessId(),
                List.copyOf(hotels), roles, scope);

        String raw = Hashing.randomToken();
        refreshTokens.save(RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(Hashing.sha256(raw))
                .expiresAt(Instant.now().plus(refreshDays, ChronoUnit.DAYS))
                .build());

        return new AuthResponse(access, raw, jwtUtil.getExpirationMs() / 1000,
                roles.keySet().stream().map(Enum::name).toList(), user.isMustChangePassword(), user.isTwoStepEnabled());
    }
}

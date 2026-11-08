package com.hospio.common.security;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies the HS256 access token every service accepts. Signed with the shared
 * {@code jwt.secret}, so identity-service mints it and every other service validates it locally.
 */
@Slf4j
@Component
public class JwtUtil {

    public static final String SCOPE_HOTEL = "hotel";
    public static final String SCOPE_BUSINESS = "business";
    public static final String SCOPE_PLATFORM = "platform";
    /** Marketers: no business, no platform powers; row-level policies grant them nothing tenant-scoped. */
    public static final String SCOPE_MARKETER = "marketer";

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration-ms:3600000}")
    private long expirationMs;

    /** Built once: the parser is immutable and thread-safe, and building one does a ServiceLoader lookup. */
    private JwtParser parser;

    @PostConstruct
    void buildParser() {
        parser = Jwts.parser().verifyWith(signingKey()).build();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public String generateToken(String userId, String email, String fullName, String businessId, List<String> hotelIds,
                                Map<Role, AccessLevel> roles, String scope) {
        List<String> roleClaims = roles.entrySet().stream().map(e -> e.getKey().name() + ":" + e.getValue().name()).toList();
        var builder = Jwts.builder()
                .subject(userId)
                .claim("email", email)
                .claim("name", fullName)
                .claim("hotels", hotelIds)
                .claim("roles", roleClaims)
                .claim("scope", scope)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs));
        if (businessId != null) {
            builder.claim("bid", businessId);
        }
        return builder.signWith(signingKey()).compact();
    }

    public Claims extractAllClaims(String token) {
        return parser.parseSignedClaims(token).getPayload();
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return !claims.getExpiration().before(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public HospioUserDetails extractUserDetails(String token) {
        Claims claims = extractAllClaims(token);
        Map<Role, AccessLevel> roles = new EnumMap<>(Role.class);
        List<String> roleClaims = claims.get("roles", List.class);
        if (roleClaims != null) {
            for (String rc : roleClaims) {
                String[] parts = rc.split(":");
                roles.put(Role.valueOf(parts[0]), parts.length > 1 ? AccessLevel.valueOf(parts[1]) : AccessLevel.FULL);
            }
        }
        List<String> hotels = claims.get("hotels", List.class);
        return new HospioUserDetails(
                claims.getSubject(),
                claims.get("email", String.class),
                claims.get("name", String.class),
                claims.get("bid", String.class),
                hotels,
                roles,
                claims.get("scope", String.class));
    }
}

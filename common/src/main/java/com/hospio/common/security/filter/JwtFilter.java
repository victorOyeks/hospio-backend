package com.hospio.common.security.filter;

import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a Bearer token and binds the {@link TenantContext} for the request from the token
 * plus the {@code X-Hotel-Id} header. A hotel the token does not grant is answered with 403.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    public static final String HOTEL_HEADER = "X-Hotel-Id";
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = extractToken(request);
        try {
            if (StringUtils.hasText(token) && jwtUtil.isTokenValid(token)) {
                HospioUserDetails user = jwtUtil.extractUserDetails(token);
                String hotelHeader = request.getHeader(HOTEL_HEADER);
                if (StringUtils.hasText(hotelHeader) && !user.isPlatform() && !user.getHotelIds().contains(hotelHeader)) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "That hotel is not granted to this user");
                    return;
                }
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
                TenantContext.set(Tenant.fromUser(user, StringUtils.hasText(hotelHeader) ? hotelHeader : null));
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}

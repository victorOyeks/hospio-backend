package com.hospio.common.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Second, independent gate on top of the normal JWT check - runs first, so a missing or wrong key
 * on a gated path short-circuits before any JWT work. Each service constructs its own instance.
 */
@Slf4j
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Api-Key";

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final List<String> internalOnlyPatterns;
    private final String expectedKey;

    public InternalApiKeyFilter(List<String> internalOnlyPatterns, String expectedKey) {
        this.internalOnlyPatterns = internalOnlyPatterns;
        this.expectedKey = expectedKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if (internalOnlyPatterns.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path))) {
            String providedKey = request.getHeader(HEADER);
            if (providedKey == null || !MessageDigest.isEqual(
                    providedKey.getBytes(StandardCharsets.UTF_8), expectedKey.getBytes(StandardCharsets.UTF_8))) {
                log.warn("Rejected request to internal-only path {} - missing or invalid {}", path, HEADER);
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "This endpoint is for internal service use only");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}

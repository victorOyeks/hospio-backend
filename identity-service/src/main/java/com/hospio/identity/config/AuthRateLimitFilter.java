package com.hospio.identity.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.instrumentation.RequestLoggingSupport;
import com.hospio.common.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A per-address backstop for the endpoints that guess passwords, PINs or send mail: sign-in, PIN sign-in, sign-up and
 * forgotten password. At most {@code hospio.auth.rate-limit-per-minute} (default 30) POSTs a minute from one client
 * address (first X-Forwarded-For hop) on this instance; more answer 429 with a plain sentence. nginx limits the same
 * paths first; this catches whatever reaches the service another way. The per-account wrong-PIN lock is separate.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    static final Set<String> LIMITED = Set.of("/auth/sign-in", "/auth/pin-sign-in", "/auth/forgot-password", "/auth/sign-up");
    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_TRACKED = 50_000;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final int perMinute;
    private final LongSupplier clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public AuthRateLimitFilter(@Value("${hospio.auth.rate-limit-per-minute:30}") int perMinute) {
        this(perMinute, System::currentTimeMillis);
    }

    AuthRateLimitFilter(int perMinute, LongSupplier clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    /** One client's count in the current minute. */
    private static final class Window {
        long start;
        int count;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !LIMITED.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (allow(RequestLoggingSupport.getClientIp(request))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JSON.writeValueAsString(ApiResponse.error("Too many attempts from this network. Wait a minute and try again")));
    }

    boolean allow(String client) {
        long now = clock.getAsLong();
        if (windows.size() > MAX_TRACKED) {
            windows.values().removeIf(w -> now - w.start >= WINDOW_MS);
        }
        Window w = windows.computeIfAbsent(client == null ? "unknown" : client, k -> new Window());
        synchronized (w) {
            if (now - w.start >= WINDOW_MS) {
                w.start = now;
                w.count = 0;
            }
            w.count++;
            return w.count <= perMinute;
        }
    }
}

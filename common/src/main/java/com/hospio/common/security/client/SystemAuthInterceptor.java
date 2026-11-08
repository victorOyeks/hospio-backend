package com.hospio.common.security.client;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Mints a short-lived platform-scoped SYSTEM token for service-to-service calls that originate from
 * a Kafka listener or scheduler rather than a user request. Signed with the shared jwt.secret, so
 * every service validates it normally. The current tenant's hotel, if any, is forwarded as a header.
 */
public class SystemAuthInterceptor implements ClientHttpRequestInterceptor {

    private static final String SYSTEM_USER_ID = "system";
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private final JwtUtil jwtUtil;

    private volatile String cachedToken;
    private volatile Instant cachedUntil = Instant.EPOCH;

    public SystemAuthInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token());
        TenantContext.current().map(Tenant::hotelId).ifPresent(h -> request.getHeaders().set("X-Hotel-Id", h));
        return execution.execute(request, body);
    }

    private String token() {
        if (cachedToken == null || Instant.now().isAfter(cachedUntil)) {
            cachedToken = jwtUtil.generateToken(SYSTEM_USER_ID, "system@hospio360.com", "Hospio System", null, List.of(),
                    Map.of(Role.SYSTEM, AccessLevel.FULL), JwtUtil.SCOPE_PLATFORM);
            cachedUntil = Instant.now().plusMillis(jwtUtil.getExpirationMs()).minus(REFRESH_MARGIN);
        }
        return cachedToken;
    }
}

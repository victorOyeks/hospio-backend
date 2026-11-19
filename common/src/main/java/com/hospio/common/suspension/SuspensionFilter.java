package com.hospio.common.suspension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Real suspension (docs/suspension.md): a request made by someone who belongs to a SUSPENDED business is refused with 403, code
 * {@value BusinessSuspension#CODE} and the header {@value BusinessSuspension#HEADER}, in every service, unless the path is on the
 * allow-list below. The filter is a bean in common, so every service registers it after the security chain (the caller is already
 * known) and before any controller or role check.
 *
 * <p>Never refused: requests with no signed-in person (public pages, webhooks), Hospio360 staff and marketers (no business), a support
 * "view as" token (read-only already, so support can still look at a suspended business), and service-to-service calls under
 * {@code /internal/**} (they carry the internal key).
 *
 * <p>Allowed while suspended, so people can sign in, see the suspended screen and the owner can pay: identity {@code /auth/**} and
 * {@code GET /users/me}; tenant {@code GET /businesses/suspension}, {@code /businesses/mine}, {@code /hotels}; platform
 * {@code /billing/**} (invoices, PDFs, receipts, pay, confirm; owner-only rules stay with the controller) and {@code /legal/**}.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class SuspensionFilter extends OncePerRequestFilter {

    private record Allowed(String service, String method, String pattern) {
    }

    /** method null = any. Paths are inside the service's context path. */
    private static final List<Allowed> ALLOWED = List.of(
            new Allowed("identity-service", null, "/auth/**"),
            new Allowed("identity-service", "GET", "/users/me"),
            new Allowed("tenant-service", "GET", "/businesses/suspension"),
            new Allowed("tenant-service", "GET", "/businesses/mine"),
            new Allowed("tenant-service", "GET", "/hotels"),
            new Allowed("platform-service", null, "/billing/**"),
            new Allowed("platform-service", null, "/legal/**"));

    private static final AntPathMatcher PATHS = new AntPathMatcher();
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final BusinessSuspension suspension;
    private final String service;

    public SuspensionFilter(BusinessSuspension suspension, @Value("${spring.application.name:}") String service) {
        this.suspension = suspension;
        this.service = service;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        HospioUserDetails user = CurrentUser.get().orElse(null);
        if (user == null || user.isPlatform() || user.isMarketer() || user.getBusinessId() == null || user.getActedBy() != null) {
            chain.doFilter(request, response);
            return;
        }
        String path = path(request);
        if (path.startsWith("/internal/") || allowed(service, request.getMethod(), path)) {
            chain.doFilter(request, response);
            return;
        }
        BusinessSuspension.Status s = suspension.of(user.getBusinessId());
        if (!s.suspended()) {
            chain.doFilter(request, response);
            return;
        }
        log.info("{} {} refused: business {} is suspended", request.getMethod(), path, user.getBusinessId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", BusinessSuspension.CODE);
        data.put("since", s.since());
        data.put("reason", s.reason());
        data.put("contact", BusinessSuspension.CONTACT);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setHeader(BusinessSuspension.HEADER, "true");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JSON.writeValueAsString(ApiResponse.error(
                "This account is suspended. Contact Hospio360 at " + BusinessSuspension.CONTACT, data)));
    }

    static boolean allowed(String service, String method, String path) {
        for (Allowed a : ALLOWED) {
            if (a.service().equals(service) && (a.method() == null || a.method().equalsIgnoreCase(method)) && PATHS.match(a.pattern(), path)) {
                return true;
            }
        }
        return false;
    }

    /** The path inside the service (no context path), with any trailing slash dropped. */
    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return uri.length() > 1 && uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }
}

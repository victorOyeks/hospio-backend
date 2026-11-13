package com.hospio.common.instrumentation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * One INFO line per request, when it ends: method, path, status, time and the user id (no names in the log). The
 * line when it starts is DEBUG.
 */
@Component
public class LoggingInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        MDC.put("reqId", UUID.randomUUID().toString());
        request.setAttribute("startTime", System.currentTimeMillis());
        if (log.isDebugEnabled()) {
            log.debug(">>> {} {} from {} | user: {}", request.getMethod(), request.getRequestURI(),
                    RequestLoggingSupport.getClientIp(request), RequestLoggingSupport.resolveUserId());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object startTime = request.getAttribute("startTime");
        long elapsed = startTime instanceof Long start ? System.currentTimeMillis() - start : -1L;
        int status = response.getStatus();
        if (ex != null && status < HttpServletResponse.SC_BAD_REQUEST) {
            status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        }
        String user = RequestLoggingSupport.resolveUserId();
        if (status >= 500) {
            log.error("<<< {} {} -> {} ({}ms) | user: {}{}", request.getMethod(), request.getRequestURI(), status, elapsed, user,
                    ex == null ? "" : " | " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } else if (status >= 400) {
            log.warn("<<< {} {} -> {} ({}ms) | user: {}", request.getMethod(), request.getRequestURI(), status, elapsed, user);
        } else {
            log.info("<<< {} {} -> {} ({}ms) | user: {}", request.getMethod(), request.getRequestURI(), status, elapsed, user);
        }
        MDC.clear();
    }
}

package com.hospio.common.instrumentation;

import com.hospio.common.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;

public final class RequestLoggingSupport {

    private RequestLoggingSupport() {
    }

    public static String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        return real != null && !real.isBlank() ? real : request.getRemoteAddr();
    }

    /** The signed-in user's id, or "anonymous": what the request log shows. */
    public static String resolveUserId() {
        return CurrentUser.get().map(u -> String.valueOf(u.getUserId())).orElse("anonymous");
    }

    public static String resolveUser() {
        return CurrentUser.get().map(u -> u.getUserId() + " (" + u.getFullName() + ")").orElse("anonymous");
    }
}

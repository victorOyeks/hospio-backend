package com.hospio.identity.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * What a session is opened from, for the sessions list (docs/profile.md). The web calls the API from its server, so it
 * forwards the browser's user agent as {@code X-Client-Agent}; a direct call has its own {@code User-Agent}. No IP address
 * is kept. {@code location} is taken only from a proxy's country header when one is there.
 */
public record ClientInfo(String userAgent, String location) {

    public static final String AGENT_HEADER = "X-Client-Agent";

    public static ClientInfo current() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return new ClientInfo(null, null);
        }
        HttpServletRequest request = attrs.getRequest();
        String agent = request.getHeader(AGENT_HEADER);
        if (!StringUtils.hasText(agent)) {
            agent = request.getHeader("User-Agent");
        }
        String country = request.getHeader("CF-IPCountry");
        String location = StringUtils.hasText(country) && country.trim().matches("[A-Za-z]{2}") && !"XX".equalsIgnoreCase(country.trim())
                ? new Locale("", country.trim().toUpperCase(Locale.ROOT)).getDisplayCountry(Locale.ENGLISH) : null;
        return new ClientInfo(StringUtils.hasText(agent) ? truncate(agent.trim(), 300) : null, location);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** "Chrome on macOS", "Safari on iPhone", "Firefox on Windows"; "Unknown browser" when there is nothing to go on. */
    public static String describe(String ua) {
        if (!StringUtils.hasText(ua)) {
            return "Unknown browser";
        }
        String browser;
        if (ua.contains("Edg/") || ua.contains("EdgA/") || ua.contains("EdgiOS/")) {
            browser = "Edge";
        } else if (ua.contains("OPR/") || ua.contains("Opera")) {
            browser = "Opera";
        } else if (ua.contains("SamsungBrowser/")) {
            browser = "Samsung Internet";
        } else if (ua.contains("Firefox/") || ua.contains("FxiOS/")) {
            browser = "Firefox";
        } else if (ua.contains("Chrome/") || ua.contains("CriOS/")) {
            browser = "Chrome";
        } else if (ua.contains("Safari/") && ua.contains("Version/")) {
            browser = "Safari";
        } else if (ua.startsWith("curl/")) {
            browser = "curl";
        } else if (ua.equals("node") || ua.startsWith("node") || ua.contains("undici") || ua.startsWith("Bun/")) {
            browser = "App server";
        } else {
            browser = "Browser";
        }
        String os;
        if (ua.contains("iPhone")) {
            os = "iPhone";
        } else if (ua.contains("iPad")) {
            os = "iPad";
        } else if (ua.contains("Android")) {
            os = "Android";
        } else if (ua.contains("Windows")) {
            os = "Windows";
        } else if (ua.contains("Mac OS X") || ua.contains("Macintosh")) {
            os = "macOS";
        } else if (ua.contains("CrOS")) {
            os = "ChromeOS";
        } else if (ua.contains("Linux")) {
            os = "Linux";
        } else {
            os = null;
        }
        return os == null ? browser : browser + " on " + os;
    }
}

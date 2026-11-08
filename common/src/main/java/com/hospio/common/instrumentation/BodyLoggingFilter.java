package com.hospio.common.instrumentation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Logs request bodies and error-response bodies so a failing call can be understood from the log
 * alone. On with {@code hospio.logging.bodies=true} (env {@code HOSPIO_LOG_BODIES}); meant for dev and
 * staging. Secrets (passwords, PINs, tokens, card data) are masked, bodies are cut at 2 KB, and
 * actuator, swagger and file uploads are skipped.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "hospio.logging", name = "bodies", havingValue = "true")
public class BodyLoggingFilter extends OncePerRequestFilter {

    private static final int MAX = 2048;
    private static final Pattern SECRETS = Pattern.compile(
            "(\"(?:password|newPassword|currentPassword|pin|refreshToken|accessToken|token|secret|apiKey|cardNumber|cvv|otp|code)\"\\s*:\\s*\")[^\"]*(\")",
            Pattern.CASE_INSENSITIVE);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String type = request.getContentType();
        return uri.contains("/actuator") || uri.contains("/swagger") || uri.contains("/v3/api-docs") || uri.contains("/webjars")
                || (type != null && type.startsWith("multipart/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request, MAX);
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(req, res);
        } finally {
            String reqBody = body(req.getContentAsByteArray(), req.getCharacterEncoding());
            int status = res.getStatus();
            String resBody = status >= 400 ? body(res.getContentAsByteArray(), res.getCharacterEncoding()) : "";
            if (!reqBody.isEmpty() && !"GET".equals(request.getMethod())) {
                log.info("    body {} {}: {}", request.getMethod(), request.getRequestURI(), mask(reqBody));
            }
            if (!resBody.isEmpty()) {
                log.warn("    response {} {} -> {}: {}", request.getMethod(), request.getRequestURI(), status, mask(resBody));
            }
            res.copyBodyToResponse();
        }
    }

    private static String body(byte[] bytes, String encoding) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        int len = Math.min(bytes.length, MAX);
        String text = new String(bytes, 0, len, encoding == null ? StandardCharsets.UTF_8.name().equals("UTF-8") ? StandardCharsets.UTF_8 : StandardCharsets.UTF_8 : charset(encoding));
        String oneLine = text.replaceAll("\\s*\\n\\s*", " ").trim();
        return bytes.length > MAX ? oneLine + " …(" + bytes.length + " bytes)" : oneLine;
    }

    private static java.nio.charset.Charset charset(String encoding) {
        try {
            return java.nio.charset.Charset.forName(encoding);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    static String mask(String json) {
        return SECRETS.matcher(json).replaceAll("$1***$2");
    }
}

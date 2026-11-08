package com.hospio.common.security.client;

import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.filter.JwtFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Forwards the inbound request's Authorization and X-Hotel-Id headers onto outbound
 * service-to-service RestClient calls, so the callee sees the same person and hotel. Calls made
 * off the request thread (Kafka listeners, schedulers) fall back to a minted system token.
 */
public class ForwardedAuthInterceptor implements ClientHttpRequestInterceptor {

    private final SystemAuthInterceptor systemFallback;

    public ForwardedAuthInterceptor(JwtUtil jwtUtil) {
        this.systemFallback = new SystemAuthInterceptor(jwtUtil);
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        HttpServletRequest inbound = currentRequest();
        String authHeader = inbound == null ? null : inbound.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null) {
            return systemFallback.intercept(request, body, execution);
        }
        request.getHeaders().set(HttpHeaders.AUTHORIZATION, authHeader);
        String hotel = inbound.getHeader(JwtFilter.HOTEL_HEADER);
        if (hotel != null) {
            request.getHeaders().set(JwtFilter.HOTEL_HEADER, hotel);
        }
        return execution.execute(request, body);
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}

package com.hospio.common.security.client;

import com.hospio.common.http.HttpTimeouts;
import com.hospio.common.security.JwtUtil;
import org.springframework.web.client.RestClient;

/** The one way to build a RestClient for another Hospio service: timeouts, forwarded auth, internal key. */
public final class ServiceClients {

    private ServiceClients() {
    }

    /** Acts as the inbound caller when there is one (their token and hotel are forwarded), as the system otherwise. */
    public static RestClient internal(String baseUrl, JwtUtil jwtUtil, String internalApiKey) {
        return HttpTimeouts.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(new ForwardedAuthInterceptor(jwtUtil))
                .requestInterceptor(new InternalApiKeyInterceptor(internalApiKey))
                .build();
    }

    /**
     * Always acts as the system, whatever request is in flight. For lookups the service makes on its own
     * authority, such as identity asking tenant which hotels a business has while it mints a token: the
     * inbound request there carries no usable token (sign-in, refresh, accept-invite) or an expired one.
     */
    public static RestClient system(String baseUrl, JwtUtil jwtUtil, String internalApiKey) {
        return HttpTimeouts.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(new SystemAuthInterceptor(jwtUtil))
                .requestInterceptor(new InternalApiKeyInterceptor(internalApiKey))
                .build();
    }
}

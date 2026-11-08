package com.hospio.common.security.client;

import com.hospio.common.http.HttpTimeouts;
import com.hospio.common.security.JwtUtil;
import org.springframework.web.client.RestClient;

/** The one way to build a RestClient for another Hospio service: timeouts, forwarded auth, internal key. */
public final class ServiceClients {

    private ServiceClients() {
    }

    public static RestClient internal(String baseUrl, JwtUtil jwtUtil, String internalApiKey) {
        return HttpTimeouts.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(new ForwardedAuthInterceptor(jwtUtil))
                .requestInterceptor(new InternalApiKeyInterceptor(internalApiKey))
                .build();
    }
}

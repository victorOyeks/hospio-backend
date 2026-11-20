package com.hospio.identity.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * What identity reads of platform-service: the admin portal's security settings (docs/sign-in.md, "Admin security settings").
 * Called as the system with the internal key. Empty when platform-service can't answer; {@code AdminSecurityPolicy} then keeps
 * the last value it read.
 */
@Slf4j
@Component
public class PlatformClient {

    /** The fields identity reads of platform's SecuritySettings. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SecuritySettings(Boolean require2Step, Integer sessionHours) {
    }

    private static final ParameterizedTypeReference<ApiResponse<SecuritySettings>> SECURITY = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public PlatformClient(@Value("${services.platform.url}") String platformUrl, JwtUtil jwtUtil,
                          @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(platformUrl, jwtUtil, internalApiKey);
    }

    /** GET /internal/settings/security; empty when unreachable, refused or unreadable. */
    public Optional<SecuritySettings> securitySettings() {
        try {
            ApiResponse<SecuritySettings> body = restClient.get().uri("/internal/settings/security").retrieve().body(SECURITY);
            return Optional.ofNullable(body == null ? null : body.data());
        } catch (Exception e) {
            log.warn("Could not read the platform security settings: {}", e.getMessage());
            return Optional.empty();
        }
    }
}

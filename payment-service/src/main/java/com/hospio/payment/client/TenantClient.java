package com.hospio.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Business names for settlement and payout rows, cached for the process lifetime (names rarely change). */
@Slf4j
@Component
public class TenantClient {

    /** The part of tenant-service's business this client reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BusinessRef(String name) {
    }

    private static final ParameterizedTypeReference<ApiResponse<BusinessRef>> BUSINESS = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final Map<String, String> names = new ConcurrentHashMap<>();

    public TenantClient(@Value("${services.tenant.url}") String tenantUrl, JwtUtil jwtUtil,
                        @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(tenantUrl, jwtUtil, internalApiKey);
    }

    public Optional<String> businessName(String businessId) {
        if (businessId == null) {
            return Optional.empty();
        }
        String cached = names.get(businessId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            ApiResponse<BusinessRef> body = restClient.get().uri("/internal/businesses/{id}", businessId).retrieve().body(BUSINESS);
            String name = body == null || body.data() == null ? null : body.data().name();
            if (name != null) {
                names.put(businessId, name);
            }
            return Optional.ofNullable(name);
        } catch (Exception e) {
            log.warn("Could not read business {} from tenant-service: {}", businessId, e.getMessage());
            return Optional.empty();
        }
    }
}

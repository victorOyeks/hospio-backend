package com.hospio.payment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Business names for settlement and payout rows, cached for the process lifetime (names rarely change). */
@Slf4j
@Component
public class TenantClient {

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
            JsonNode body = restClient.get().uri("/internal/businesses/{id}", businessId).retrieve().body(JsonNode.class);
            String name = body == null ? null : body.path("data").path("name").asText(null);
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

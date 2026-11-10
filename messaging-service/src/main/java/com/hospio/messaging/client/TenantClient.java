package com.hospio.messaging.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Who a message is from: a hotel's name and email (the From name and the Reply-To), else the business's. Read from
 * tenant-service as the system and kept for 10 minutes; empty when tenant-service can't be reached.
 */
@Slf4j
@Component
public class TenantClient {

    private static final Duration TTL = Duration.ofMinutes(10);

    public record Sender(String name, String email) {
    }

    private record Cached(Optional<Sender> value, Instant at) {
    }

    private final RestClient restClient;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public TenantClient(@Value("${services.tenant.url}") String tenantUrl, JwtUtil jwtUtil,
                        @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = ServiceClients.system(tenantUrl, jwtUtil, internalApiKey);
    }

    public Optional<Sender> hotel(String hotelId) {
        return hotelId == null ? Optional.empty() : cached("H:" + hotelId, "/internal/hotels/{id}", hotelId);
    }

    public Optional<Sender> business(String businessId) {
        return businessId == null ? Optional.empty() : cached("B:" + businessId, "/internal/businesses/{id}", businessId);
    }

    private Optional<Sender> cached(String key, String uri, String id) {
        Cached c = cache.get(key);
        if (c != null && c.at().plus(TTL).isAfter(Instant.now())) {
            return c.value();
        }
        try {
            JsonNode body = restClient.get().uri(uri, id).retrieve().body(JsonNode.class);
            JsonNode d = body == null ? null : body.path("data");
            Optional<Sender> v = d == null || d.isMissingNode() || d.isNull() || !d.hasNonNull("name") ? Optional.empty()
                    : Optional.of(new Sender(d.path("name").asText(), blankToNull(d.path("email").asText(null))));
            cache.put(key, new Cached(v, Instant.now()));
            return v;
        } catch (Exception e) {
            log.warn("Could not read {} from tenant-service: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

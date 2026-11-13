package com.hospio.messaging.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Who a message is from: a hotel's name and email (the From name and the Reply-To), else the business's. Read from
 * tenant-service as the system and kept for 10 minutes (at most 10,000 entries); empty when tenant-service can't be reached
 * (retried after 30 seconds).
 */
@Slf4j
@Component
public class TenantClient {

    private static final Duration TTL = Duration.ofMinutes(10);

    public record Sender(String name, String email) {
    }

    private final RestClient restClient;
    /** At most 10,000 hotels and businesses; a failed lookup is retried after 30 seconds. */
    private final TtlCache<Optional<Sender>> cache = new TtlCache<>(10_000, TTL, Duration.ofSeconds(30));

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
        return cache.get(key, () -> {
            try {
                JsonNode body = restClient.get().uri(uri, id).retrieve().body(JsonNode.class);
                JsonNode d = body == null ? null : body.path("data");
                return TtlCache.Loaded.ok(d == null || d.isMissingNode() || d.isNull() || !d.hasNonNull("name") ? Optional.<Sender>empty()
                        : Optional.of(new Sender(d.path("name").asText(), blankToNull(d.path("email").asText(null)))));
            } catch (Exception e) {
                log.warn("Could not read {} from tenant-service: {}", key, e.getMessage());
                return TtlCache.Loaded.failed(Optional.<Sender>empty());
            }
        });
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

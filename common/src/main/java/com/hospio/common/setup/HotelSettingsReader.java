package com.hospio.common.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Reads one Hotel setup section from hotel-config ({@code GET /internal/settings/{section}?hotelId=}) with the
 * internal API key, cached per hotel and section for 60 seconds. An unreachable hotel-config answers empty
 * (callers fall back to their own defaults) and is not cached, so the next call tries again.
 */
public class HotelSettingsReader {

    private static final Logger log = LoggerFactory.getLogger(HotelSettingsReader.class);
    private static final Duration TTL = Duration.ofSeconds(60);

    private record Cached(JsonNode data, Instant until) {
    }

    private final RestClient restClient;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public HotelSettingsReader(String hotelConfigUrl, JwtUtil jwtUtil, String internalApiKey) {
        this.restClient = ServiceClients.internal(hotelConfigUrl, jwtUtil, internalApiKey);
    }

    /** The section's values (defaults laid over the hotel's own by hotel-config), or empty when hotel-config can't be reached. */
    public Optional<JsonNode> section(String hotelId, String section) {
        if (hotelId == null) {
            return Optional.empty();
        }
        String key = hotelId + "|" + section;
        Cached c = cache.get(key);
        if (c != null && c.until().isAfter(Instant.now())) {
            return Optional.of(c.data());
        }
        try {
            JsonNode body = restClient.get().uri("/internal/settings/{s}?hotelId={h}", section, hotelId).retrieve().body(JsonNode.class);
            JsonNode data = body == null ? null : body.path("data");
            if (data == null || data.isMissingNode() || data.isNull() || data.isEmpty()) {
                return Optional.empty();
            }
            cache.put(key, new Cached(data, Instant.now().plus(TTL)));
            return Optional.of(data);
        } catch (Exception e) {
            log.warn("Could not read {} settings for hotel {}: {}", section, hotelId, e.getMessage());
            return Optional.empty();
        }
    }
}

package com.hospio.common.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Reads one Hotel setup section from hotel-config ({@code GET /internal/settings/{section}?hotelId=}) with the
 * internal API key, cached per hotel and section for 60 seconds. An unreachable hotel-config answers empty
 * (callers fall back to their own defaults) and that empty answer is kept for 30 seconds, so a down hotel-config
 * is not called on every request.
 *
 * The cache is shared by every reader in the JVM (several clients of one service each build their own reader for
 * the same hotel-config) and bounded: at most {@value #MAX_ENTRIES} hotel/section pairs, the least recently used
 * going first.
 */
public class HotelSettingsReader {

    private static final Logger log = LoggerFactory.getLogger(HotelSettingsReader.class);
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final Duration FAILURE_TTL = Duration.ofSeconds(30);
    static final int MAX_ENTRIES = 5_000;

    /** {@code data} is null when the answer was empty or hotel-config could not be reached. */
    private record Cached(JsonNode data, Instant until) {
    }

    private static final Map<String, Cached> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private final String baseUrl;
    private final RestClient restClient;

    public HotelSettingsReader(String hotelConfigUrl, JwtUtil jwtUtil, String internalApiKey) {
        this.baseUrl = hotelConfigUrl;
        this.restClient = ServiceClients.internal(hotelConfigUrl, jwtUtil, internalApiKey);
    }

    /** The section's values (defaults laid over the hotel's own by hotel-config), or empty when hotel-config can't be reached. */
    public Optional<JsonNode> section(String hotelId, String section) {
        if (hotelId == null) {
            return Optional.empty();
        }
        return cached(baseUrl + "|" + hotelId + "|" + section, () -> fetch(hotelId, section));
    }

    /** Reads {@code key} from the shared cache, or loads it; a null load (empty or failed) is remembered for less time. */
    static Optional<JsonNode> cached(String key, Supplier<Optional<JsonNode>> load) {
        Instant now = Instant.now();
        Cached c;
        synchronized (CACHE) {
            c = CACHE.get(key);
        }
        if (c != null && c.until().isAfter(now)) {
            return Optional.ofNullable(c.data());
        }
        Optional<JsonNode> loaded = load.get();
        Cached fresh = new Cached(loaded.orElse(null), now.plus(loaded.isPresent() ? TTL : FAILURE_TTL));
        synchronized (CACHE) {
            CACHE.put(key, fresh);
        }
        return loaded;
    }

    static int cacheSize() {
        synchronized (CACHE) {
            return CACHE.size();
        }
    }

    static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private Optional<JsonNode> fetch(String hotelId, String section) {
        try {
            JsonNode body = restClient.get().uri("/internal/settings/{s}?hotelId={h}", section, hotelId).retrieve().body(JsonNode.class);
            JsonNode data = body == null ? null : body.path("data");
            if (data == null || data.isMissingNode() || data.isNull() || data.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(data);
        } catch (Exception e) {
            log.warn("Could not read {} settings for hotel {}: {}", section, hotelId, e.getMessage());
            return Optional.empty();
        }
    }
}

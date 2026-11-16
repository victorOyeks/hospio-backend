package com.hospio.common.integrations.settings;

import com.hospio.common.response.ApiResponse;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.client.ServiceClients;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;
import org.springframework.web.client.RestClient;

/**
 * Reads the integration settings saved in the admin portal from platform-service ({@code GET /internal/integrations/{provider}}, the
 * internal API key) as the provider's typed record, cached for 60 seconds per provider, so a change in the portal is in force within
 * about a minute without a restart. {@link #effective} lays them over the caller's environment values: with nothing saved, or before
 * platform-service has ever answered, every caller works exactly as it does from the environment alone.
 *
 * <p>When platform-service can't be reached, the last answer it gave is kept (a short outage never switches providers back to the
 * environment mid-day) and asked again after 30 seconds; with no earlier answer, nothing is saved as far as the caller knows.
 */
public class IntegrationSettingsReader {

    private static final Logger log = LoggerFactory.getLogger(IntegrationSettingsReader.class);
    static final Duration TTL = Duration.ofSeconds(60);
    static final Duration FAILURE_TTL = Duration.ofSeconds(30);

    /** {@code data} is null when nothing is saved for the provider (or platform-service was never reached). */
    private record Cached(IntegrationSettings data, Instant until) {
    }

    private final RestClient restClient;
    private final Map<IntegrationProvider, Cached> cache = new ConcurrentHashMap<>();

    public IntegrationSettingsReader(String platformUrl, JwtUtil jwtUtil, String internalApiKey) {
        this(ServiceClients.system(platformUrl, jwtUtil, internalApiKey));
    }

    public IntegrationSettingsReader(RestClient restClient) {
        this.restClient = restClient;
    }

    /** What the portal has saved for this provider (cached); empty when nothing is saved. */
    public <T extends IntegrationSettings> Optional<T> saved(Class<T> type) {
        IntegrationProvider p = IntegrationProvider.of(type);
        Cached c = cache.get(p);
        if (c != null && c.until().isAfter(Instant.now())) {
            return Optional.ofNullable(type.cast(c.data()));
        }
        return load(p, type, c);
    }

    /** As {@link #saved}, asked of platform-service now (the portal's Test button, right after a save). */
    public <T extends IntegrationSettings> Optional<T> fresh(Class<T> type) {
        IntegrationProvider p = IntegrationProvider.of(type);
        return load(p, type, cache.get(p));
    }

    /** The settings in force: each field saved in the portal, else {@code env}'s. */
    public <T extends IntegrationSettings> T effective(T env) {
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) env.getClass();
        return IntegrationCatalogue.overlay(saved(type).orElse(null), env);
    }

    /** As {@link #effective}, read fresh from platform-service. */
    public <T extends IntegrationSettings> T effectiveNow(T env) {
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) env.getClass();
        return IntegrationCatalogue.overlay(fresh(type).orElse(null), env);
    }

    private <T extends IntegrationSettings> Optional<T> load(IntegrationProvider p, Class<T> type, Cached previous) {
        try {
            ParameterizedTypeReference<ApiResponse<T>> answer = ParameterizedTypeReference.forType(
                    ResolvableType.forClassWithGenerics(ApiResponse.class, type).getType());
            ApiResponse<T> body = restClient.get().uri("/internal/integrations/{p}", p.name()).retrieve().body(answer);
            T data = body == null ? null : body.data();
            cache.put(p, new Cached(data, Instant.now().plus(TTL)));
            return Optional.ofNullable(data);
        } catch (Exception e) {
            IntegrationSettings kept = previous == null ? null : previous.data();
            log.warn("Could not read the {} integration settings from platform-service ({}); {}", p, e.getMessage(),
                    kept == null ? "using the server's environment" : "keeping the last settings read");
            cache.put(p, new Cached(kept, Instant.now().plus(FAILURE_TTL)));
            return Optional.ofNullable(type.cast(kept));
        }
    }

    /** Tests: forget every answer. */
    void clear() {
        cache.clear();
    }
}

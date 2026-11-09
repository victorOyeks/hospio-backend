package com.hospio.platform.service;

import com.hospio.platform.client.TenantClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Business names for rows that were written before the name was snapshotted, cached for ten minutes. */
@Component
@RequiredArgsConstructor
public class BusinessNames {

    private static final Duration TTL = Duration.ofMinutes(10);

    private record Cached(TenantClient.Business business, Instant until) {
    }

    private final TenantClient tenant;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public Optional<TenantClient.Business> business(String businessId) {
        if (businessId == null) {
            return Optional.empty();
        }
        Cached c = cache.get(businessId);
        if (c != null && c.until().isAfter(Instant.now())) {
            return Optional.ofNullable(c.business());
        }
        Optional<TenantClient.Business> b = tenant.business(businessId);
        b.ifPresent(v -> cache.put(businessId, new Cached(v, Instant.now().plus(TTL))));
        return b;
    }

    public String name(String businessId) {
        return business(businessId).map(TenantClient.Business::name).orElse(null);
    }
}

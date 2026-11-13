package com.hospio.messaging.client;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A small bounded cache for lookups over HTTP (hotel names, brands): least recently used entries go first once
 * {@code maxSize} is reached, a good answer is kept for {@code ttl}, a failed lookup only for {@code failureTtl} (so a
 * tenant-service hiccup is retried soon, but a burst of sends does not hammer a service that is down).
 */
final class TtlCache<V> {

    private record Entry<V>(V value, Instant until) {
    }

    /** A lookup's answer and whether it is a real one (kept for the full ttl) or a fallback after a failure. */
    record Loaded<V>(V value, boolean ok) {
        static <V> Loaded<V> ok(V value) {
            return new Loaded<>(value, true);
        }

        static <V> Loaded<V> failed(V fallback) {
            return new Loaded<>(fallback, false);
        }
    }

    private final Duration ttl;
    private final Duration failureTtl;
    private final Map<String, Entry<V>> map;

    TtlCache(int maxSize, Duration ttl, Duration failureTtl) {
        this.ttl = ttl;
        this.failureTtl = failureTtl;
        this.map = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry<V>> eldest) {
                return size() > maxSize;
            }
        };
    }

    /** The cached value, else {@code load}'s (the load runs outside the lock; two threads may both load a missing key). */
    V get(String key, Supplier<Loaded<V>> load) {
        Instant now = Instant.now();
        synchronized (map) {
            Entry<V> e = map.get(key);
            if (e != null && e.until().isAfter(now)) {
                return e.value();
            }
        }
        Loaded<V> loaded = load.get();
        synchronized (map) {
            map.put(key, new Entry<>(loaded.value(), Instant.now().plus(loaded.ok() ? ttl : failureTtl)));
        }
        return loaded.value();
    }

    int size() {
        synchronized (map) {
            return map.size();
        }
    }
}

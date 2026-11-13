package com.hospio.messaging.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The lookup cache: bounded, good answers kept, failures retried soon. */
class TtlCacheTest {

    @Test
    void keepsAtMostMaxSizeAndRetriesAFailureAfterItsShortTtl() throws Exception {
        TtlCache<String> cache = new TtlCache<>(3, Duration.ofMinutes(10), Duration.ofMillis(20));
        for (int i = 0; i < 10; i++) {
            int n = i;
            cache.get("k" + i, () -> TtlCache.Loaded.ok("v" + n));
        }
        assertThat(cache.size()).isEqualTo(3);

        AtomicInteger loads = new AtomicInteger();
        cache.get("ok", () -> TtlCache.Loaded.ok("x" + loads.incrementAndGet()));
        assertThat(cache.get("ok", () -> TtlCache.Loaded.ok("x" + loads.incrementAndGet()))).isEqualTo("x1");

        AtomicInteger fails = new AtomicInteger();
        cache.get("down", () -> TtlCache.Loaded.failed("fallback" + fails.incrementAndGet()));
        assertThat(cache.get("down", () -> TtlCache.Loaded.failed("fallback" + fails.incrementAndGet()))).isEqualTo("fallback1");
        Thread.sleep(40);
        assertThat(cache.get("down", () -> TtlCache.Loaded.ok("back"))).isEqualTo("back");
    }
}

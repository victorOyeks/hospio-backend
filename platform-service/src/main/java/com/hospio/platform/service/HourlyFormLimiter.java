package com.hospio.platform.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * How many times one client address and one email may send a public form in an hour (the forms have no login). Counted in Redis
 * so every instance shares the count; when Redis can't be reached, counted in this instance's memory instead, so the form keeps
 * working and is still limited. Each form has its own key prefix.
 */
@Slf4j
abstract class HourlyFormLimiter {

    static final Duration WINDOW = Duration.ofHours(1);
    private static final int MAX_TRACKED = 20_000;

    private final StringRedisTemplate redis;
    private final String prefix;
    private final int perAddress;
    private final int perEmail;
    private final Map<String, long[]> local = new ConcurrentHashMap<>();

    HourlyFormLimiter(StringRedisTemplate redis, String prefix, int perAddress, int perEmail) {
        this.redis = redis;
        this.prefix = prefix;
        this.perAddress = perAddress;
        this.perEmail = perEmail;
    }

    /** Counts this attempt for the address; false when it is over the hour's limit. */
    public boolean allowAddress(String address) {
        return count("ip:" + (address == null || address.isBlank() ? "unknown" : address)) <= perAddress;
    }

    /** Counts this attempt for the email (lower case); false when it is over the hour's limit. */
    public boolean allowEmail(String email) {
        return count("email:" + sha256(email)) <= perEmail;
    }

    /** The email is never a Redis key in the clear. */
    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private long count(String key) {
        if (redis != null) {
            try {
                Long n = redis.opsForValue().increment(prefix + key);
                if (n != null) {
                    // The second check covers a first call whose expire never arrived: a counter must not live for ever.
                    if (n == 1L || Long.valueOf(-1L).equals(redis.getExpire(prefix + key))) {
                        redis.expire(prefix + key, WINDOW);
                    }
                    return n;
                }
            } catch (RuntimeException e) {
                log.warn("Form limit ({}): Redis not reached ({}); counting in memory", prefix, e.getClass().getSimpleName());
            }
        }
        long now = System.currentTimeMillis();
        if (local.size() > MAX_TRACKED) {
            local.values().removeIf(w -> now - w[0] >= WINDOW.toMillis());
        }
        long[] w = local.computeIfAbsent(key, k -> new long[]{now, 0});
        synchronized (w) {
            if (now - w[0] >= WINDOW.toMillis()) {
                w[0] = now;
                w[1] = 0;
            }
            return ++w[1];
        }
    }
}

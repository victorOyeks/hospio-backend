package com.hospio.platform.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * How many partner applications one client address and one email may send in an hour (the public form has no login). Counted in
 * Redis so every instance shares the count; when Redis can't be reached, counted in this instance's memory instead, so the
 * form keeps working and is still limited.
 */
@Slf4j
@Component
public class PartnerApplicationLimiter {

    static final Duration WINDOW = Duration.ofHours(1);
    private static final String PREFIX = "partner-apply:";
    private static final int MAX_TRACKED = 20_000;

    private final StringRedisTemplate redis;
    private final int perAddress;
    private final int perEmail;
    private final Map<String, long[]> local = new ConcurrentHashMap<>();

    @Autowired
    public PartnerApplicationLimiter(ObjectProvider<StringRedisTemplate> redis,
                                     @Value("${hospio.partner-applications.per-address-per-hour:3}") int perAddress,
                                     @Value("${hospio.partner-applications.per-email-per-hour:3}") int perEmail) {
        this(redis.getIfAvailable(), perAddress, perEmail);
    }

    PartnerApplicationLimiter(StringRedisTemplate redis, int perAddress, int perEmail) {
        this.redis = redis;
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
                Long n = redis.opsForValue().increment(PREFIX + key);
                if (n != null) {
                    // The second check covers a first call whose expire never arrived: a counter must not live for ever.
                    if (n == 1L || Long.valueOf(-1L).equals(redis.getExpire(PREFIX + key))) {
                        redis.expire(PREFIX + key, WINDOW);
                    }
                    return n;
                }
            } catch (RuntimeException e) {
                log.warn("Partner application limit: Redis not reached ({}); counting in memory", e.getClass().getSimpleName());
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

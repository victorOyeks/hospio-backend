package com.hospio.platform.system;

import com.hospio.platform.system.SystemHealthDtos.Redis;
import java.util.List;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Redis INFO (read-only): version, memory used and peak, the memory limit (0 = none), keys across every database, uptime. The
 * caller bounds the wait; nothing is written.
 */
@Slf4j
@Component
public class RedisProbe {

    private final ObjectProvider<RedisConnectionFactory> factory;

    public RedisProbe(ObjectProvider<RedisConnectionFactory> factory) {
        this.factory = factory;
    }

    public Redis probe() {
        RedisConnectionFactory f = factory.getIfAvailable();
        if (f == null) {
            return new Redis(false, "Not configured for this service", null, null, null, null, null, null, SystemHealthDtos.WARNING);
        }
        try (RedisConnection c = f.getConnection()) {
            Properties info = c.serverCommands().info();
            return fromInfo(info == null ? new Properties() : info);
        } catch (Exception e) {
            log.warn("System health: Redis not readable: {}", e.toString());
            return new Redis(false, "Not reachable: " + e.getClass().getSimpleName(), null, null, null, null, null, null, SystemHealthDtos.PROBLEM);
        }
    }

    static Redis fromInfo(Properties info) {
        long keys = 0;
        for (String name : info.stringPropertyNames()) {
            if (name.matches("db\\d+")) {
                keys += keysOf(info.getProperty(name));
            }
        }
        return new Redis(true, null, info.getProperty("redis_version"), number(info.getProperty("used_memory")), number(info.getProperty("used_memory_peak")),
                number(info.getProperty("maxmemory")), keys, number(info.getProperty("uptime_in_seconds")), SystemHealthDtos.OK);
    }

    /** "keys=37,expires=37,avg_ttl=…" → 37. */
    static long keysOf(String line) {
        if (line == null) {
            return 0;
        }
        for (String part : List.of(line.split(","))) {
            if (part.startsWith("keys=")) {
                Long n = number(part.substring(5));
                return n == null ? 0 : n;
            }
        }
        return 0;
    }

    private static Long number(String v) {
        try {
            return v == null ? null : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

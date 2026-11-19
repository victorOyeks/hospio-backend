package com.hospio.platform.system;

import java.time.Instant;
import java.util.List;

/**
 * Platform → System health (docs/system-health.md): what GET /platform/admin/system-health answers. Sizes are bytes, percentages
 * 0-100, durations seconds. Every probe fills its own part and says why when it couldn't ({@code error}); nothing here ever holds a
 * secret, an environment value or a connection string.
 */
public final class SystemHealthDtos {

    private SystemHealthDtos() {
    }

    /** OK, WARNING or PROBLEM: the colour of a row and of the banner. */
    public static final String OK = "OK";
    public static final String WARNING = "WARNING";
    public static final String PROBLEM = "PROBLEM";

    public static int rank(String level) {
        return PROBLEM.equals(level) ? 2 : WARNING.equals(level) ? 1 : 0;
    }

    public static String worst(String a, String b) {
        return rank(a) >= rank(b) ? a : b;
    }

    public record Report(Instant at, String overall, List<Reason> reasons, List<String> notes, Server server, List<ServiceRow> services,
                         Database database, Kafka kafka, Redis redis, List<Cert> certs, History history, Thresholds thresholds,
                         List<OtherApp> otherApps, double collectSeconds) {
    }

    /** One line of the banner: level, the area (Disk, Services...), and what is wrong in words. */
    public record Reason(String level, String area, String text) {
    }

    public record Server(String hostname, String os, String kernel, Long uptimeSeconds, Instant serverTime, String timezone, int cores,
                         Double load1, Double load5, Double load15, Double cpuPct, Long memTotal, Long memUsed, Long memAvailable,
                         Long swapTotal, Long swapUsed, Double memoryStallPct, List<Mount> mounts, List<DirSize> largest,
                         Long hospioMemory, String level, String error) {
    }

    /** One disk (df): total, used, free for ordinary programs, % used the way df counts it. */
    public record Mount(String path, String device, String type, long total, long used, long free, double usedPct, String level) {
    }

    /** A folder's size, measured in the background (null while it is being measured or when it can't be read). */
    public record DirSize(String label, String path, Long bytes, String note) {
    }

    /**
     * One Hospio unit. kind API (a Spring service, health from /actuator/health) or WEB (a web app, health = its page answers).
     * state is systemd's ActiveState (active, failed, inactive, activating = restarting); health UP, DOWN or UNKNOWN.
     */
    public record ServiceRow(String key, String name, String kind, String unit, Integer port, String state, String subState, String health,
                             String healthDetail, String level, String commit, Instant deployedAt, Instant startedAt, Long uptimeSeconds,
                             Integer restarts, Long memoryBytes, Double cpuPct) {
    }

    public record NamedSize(String name, long bytes) {
    }

    public record TableSize(String schema, String table, long bytes, long liveRows, long deadRows, Instant lastAutovacuum) {
    }

    public record Backups(boolean scheduled, String detail) {
    }

    public record Database(boolean up, String version, String error, Long totalBytes, Long hospioBytes, List<NamedSize> databases,
                           List<NamedSize> schemas, Integer connections, Integer maxConnections, Double longestQuerySeconds,
                           List<TableSize> largestTables, Double cacheHitPct, Long deadRows, Integer tablesNeedingVacuum, Backups backups,
                           String level) {
    }

    /** A consumer group's lag (messages waiting) and whether it is growing; stuck = lag over the limit with no progress for minutes. */
    public record ConsumerGroup(String group, String service, long lag, String trend, Integer stuckMinutes, String level) {
    }

    public record Kafka(boolean up, String error, Integer brokers, Integer topics, Long logBytes, List<ConsumerGroup> groups, String level) {
    }

    public record Redis(boolean up, String error, String version, Long usedBytes, Long peakBytes, Long maxBytes, Long keys, Long uptimeSeconds,
                        String level) {
    }

    public record Cert(String host, Long daysLeft, Instant expiresAt, String issuer, String error, String level) {
    }

    public record Point(Instant at, Double cpuPct, Double memPct, Double swapPct, Double diskPct, Long diskUsed, Long dbBytes,
                        Integer servicesUp, Integer servicesTotal) {
    }

    /** day: every 5-minute snapshot of the last 24 hours; week: hourly averages over 7 days. */
    public record History(List<Point> day, List<Point> week, Double diskGrowthPerDay, Double diskFullInDays, String diskTrend) {
    }

    /** The limits in force (Settings → System health), so the page can say what it measures against. */
    public record Thresholds(int diskWarnPct, int diskCriticalPct, int memoryPct, int swapPct, int memoryMinutes, int serviceDownMinutes,
                             int certDays, int kafkaLag, int kafkaStuckMinutes, int dbConnectionsPct, boolean emailAlerts) {
    }

    /** Something else running on the same server (another product, Docker, Postgres): its memory counts in the server's totals. */
    public record OtherApp(String unit, String name, Long memoryBytes) {
    }
}

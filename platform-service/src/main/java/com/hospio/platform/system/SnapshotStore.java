package com.hospio.platform.system;

import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.system.SystemHealthDtos.History;
import com.hospio.platform.system.SystemHealthDtos.Point;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * platform.system_snapshots: one row every 5 minutes (CPU, memory, swap, the main disk, the Hospio database's size, services up),
 * kept 7 days. Read back as the last 24 hours (every snapshot) and 7 days of hourly averages, with the main disk's growth per day
 * and when it would be full at that rate.
 */
@Component
public class SnapshotStore {

    static final Duration KEEP = Duration.ofDays(7);
    /** The disk estimate needs this much history and at least this much growth to say anything. */
    static final Duration MIN_SPAN = Duration.ofHours(6);
    static final long MIN_GROWTH = 50L * 1024 * 1024;

    private final JdbcTemplate jdbc;

    public SnapshotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Snapshot(Instant at, Double cpuPct, Double load1, Double memPct, Double swapPct, Double diskPct, Long diskUsed, Long diskTotal,
                           Long dbBytes, Integer servicesUp, Integer servicesTotal) {
    }

    public void save(Snapshot s) {
        TenantContext.runWith(Tenant.system("system-health"), () -> {
            jdbc.update("INSERT INTO platform.system_snapshots (taken_at, cpu_pct, load1, mem_pct, swap_pct, disk_pct, disk_used, disk_total, db_bytes, "
                            + "services_up, services_total) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (taken_at) DO NOTHING",
                    Timestamp.from(s.at().truncatedTo(ChronoUnit.SECONDS)), s.cpuPct(), s.load1(), s.memPct(), s.swapPct(), s.diskPct(), s.diskUsed(),
                    s.diskTotal(), s.dbBytes(), s.servicesUp(), s.servicesTotal());
            jdbc.update("DELETE FROM platform.system_snapshots WHERE taken_at < ?", Timestamp.from(Instant.now().minus(KEEP)));
            return null;
        });
    }

    public Instant latest() {
        return TenantContext.runWith(Tenant.system("system-health"), () -> {
            Timestamp t = jdbc.queryForObject("SELECT max(taken_at) FROM platform.system_snapshots", Timestamp.class);
            return t == null ? null : t.toInstant();
        });
    }

    public History history() {
        List<Snapshot> all = TenantContext.runWith(Tenant.system("system-health"), () -> jdbc.query(
                "SELECT * FROM platform.system_snapshots WHERE taken_at >= ? ORDER BY taken_at", SnapshotStore::row,
                Timestamp.from(Instant.now().minus(KEEP))));
        return history(all, Instant.now());
    }

    static History history(List<Snapshot> all, Instant now) {
        Instant dayAgo = now.minus(Duration.ofDays(1));
        List<Point> day = all.stream().filter(s -> !s.at().isBefore(dayAgo)).map(SnapshotStore::point).toList();
        Map<Instant, List<Snapshot>> hours = new TreeMap<>();
        for (Snapshot s : all) {
            hours.computeIfAbsent(s.at().truncatedTo(ChronoUnit.HOURS), k -> new ArrayList<>()).add(s);
        }
        List<Point> week = new ArrayList<>();
        hours.forEach((hour, list) -> week.add(new Point(hour, avg(list, Snapshot::cpuPct), avg(list, Snapshot::memPct), avg(list, Snapshot::swapPct),
                avg(list, Snapshot::diskPct), last(list).diskUsed(), last(list).dbBytes(), min(list), last(list).servicesTotal())));
        Double perDay = growthPerDay(all);
        Double fullIn = null;
        String trend = "Not enough history yet";
        if (perDay != null) {
            Snapshot newest = all.get(all.size() - 1);
            if (perDay > 0 && newest.diskTotal() != null && newest.diskUsed() != null) {
                // Free the way df shows it is a little less than total - used (reserved blocks), so this errs early, not late.
                fullIn = Math.round((newest.diskTotal() - newest.diskUsed()) / perDay * 10.0) / 10.0;
                trend = "GROWING";
            } else {
                trend = perDay < 0 ? "SHRINKING" : "STEADY";
            }
        }
        return new History(day, week, perDay, fullIn, trend);
    }

    /**
     * Bytes a day the main disk grows, by a least-squares line through the snapshots; null without 6 hours of history, and 0 when it
     * changed by less than 50 MB over the whole span (too little to call a trend).
     */
    static Double growthPerDay(List<Snapshot> all) {
        List<Snapshot> s = all.stream().filter(x -> x.diskUsed() != null).toList();
        if (s.size() < 3 || Duration.between(s.get(0).at(), s.get(s.size() - 1).at()).compareTo(MIN_SPAN) < 0) {
            return null;
        }
        long first = s.get(0).diskUsed();
        long lastUsed = s.get(s.size() - 1).diskUsed();
        long lo = s.stream().mapToLong(Snapshot::diskUsed).min().orElse(first);
        long hi = s.stream().mapToLong(Snapshot::diskUsed).max().orElse(first);
        if (hi - lo < MIN_GROWTH && Math.abs(lastUsed - first) < MIN_GROWTH) {
            return 0.0;
        }
        double t0 = s.get(0).at().getEpochSecond();
        double n = s.size();
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double sxy = 0;
        for (Snapshot x : s) {
            double t = (x.at().getEpochSecond() - t0) / 86400.0;
            double y = x.diskUsed();
            sx += t;
            sy += y;
            sxx += t * t;
            sxy += t * y;
        }
        double den = n * sxx - sx * sx;
        return den == 0 ? null : (double) Math.round((n * sxy - sx * sy) / den);
    }

    private static Point point(Snapshot s) {
        return new Point(s.at(), s.cpuPct(), s.memPct(), s.swapPct(), s.diskPct(), s.diskUsed(), s.dbBytes(), s.servicesUp(), s.servicesTotal());
    }

    private static Double avg(List<Snapshot> list, java.util.function.Function<Snapshot, Double> f) {
        double sum = 0;
        int n = 0;
        for (Snapshot s : list) {
            Double v = f.apply(s);
            if (v != null) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? null : Math.round(sum / n * 10.0) / 10.0;
    }

    private static Integer min(List<Snapshot> list) {
        return list.stream().map(Snapshot::servicesUp).filter(java.util.Objects::nonNull).min(Integer::compare).orElse(null);
    }

    private static Snapshot last(List<Snapshot> list) {
        return list.get(list.size() - 1);
    }

    private static Snapshot row(ResultSet rs, int i) throws SQLException {
        return new Snapshot(rs.getTimestamp("taken_at").toInstant(), dbl(rs, "cpu_pct"), dbl(rs, "load1"), dbl(rs, "mem_pct"), dbl(rs, "swap_pct"),
                dbl(rs, "disk_pct"), lng(rs, "disk_used"), lng(rs, "disk_total"), lng(rs, "db_bytes"), integer(rs, "services_up"),
                integer(rs, "services_total"));
    }

    private static Double dbl(ResultSet rs, String c) throws SQLException {
        java.math.BigDecimal v = rs.getBigDecimal(c);
        return v == null ? null : v.doubleValue();
    }

    private static Long lng(ResultSet rs, String c) throws SQLException {
        long v = rs.getLong(c);
        return rs.wasNull() ? null : v;
    }

    private static Integer integer(ResultSet rs, String c) throws SQLException {
        int v = rs.getInt(c);
        return rs.wasNull() ? null : v;
    }
}

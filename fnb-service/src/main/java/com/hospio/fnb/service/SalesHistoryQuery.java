package com.hospio.fnb.service;

import com.hospio.fnb.rules.SaleBucket;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The sales history in SQL: which orders match, one page of their ids newest first, and the money over every match (totals, per outlet,
 * per attendant). Timestamps are stored as UTC wall time (as Hibernate writes them), so instants are bound as UTC {@link LocalDateTime}.
 * Each breakdown ignores its own filter (the per-attendant table still lists every attendant when one is picked), so with no outlet or
 * attendant filter the breakdowns add up to the totals.
 */
@Component
@RequiredArgsConstructor
public class SalesHistoryQuery {

    /** CASH, CARD and TRANSFER are payments taken; ROOM is a charge to a room. */
    public static final Set<String> METHODS = Set.of("CASH", "CARD", "TRANSFER", "ROOM");

    public enum Dim { OUTLET, ATTENDANT }

    /**
     * {@code outletIds} are the outlets the caller may see (already narrowed by an outlet filter); {@code buckets} the statuses wanted;
     * {@code method}, {@code attendant} (a user id) and {@code search} are optional.
     */
    public record Filter(String hotelId, Collection<String> outletIds, Instant from, Instant to, Set<SaleBucket> buckets, String method,
                         String attendant, String search, Collection<String> allOutletIds) {
    }

    public record Money3(long sales, BigDecimal sold, BigDecimal paid, BigDecimal unpaid, BigDecimal onRooms, long cancelled, BigDecimal cancelledAmount) {
        static Money3 empty() {
            return new Money3(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO);
        }
    }

    public record Group(String key, String name, Money3 money) {
    }

    public record Earlier(long count, BigDecimal amount, Instant oldest) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    /** The ids of one page, newest first by the moment the sale was opened. */
    public List<String> pageIds(Filter f, int page, int size) {
        if (f.outletIds().isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = "SELECT s.id FROM (" + base(f, p, EnumSet.noneOf(Dim.class)) + ") s" + bucketWhere(f, p)
                + " ORDER BY s.created_at DESC, s.number DESC, s.id LIMIT :limit OFFSET :offset";
        p.addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.queryForList(sql, p, String.class);
    }

    /** Count and money over every matching sale. {@code sales} and the money leave cancelled sales out; {@code cancelled} counts them. */
    public Money3 totals(Filter f) {
        if (f.outletIds().isEmpty()) {
            return Money3.empty();
        }
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = "SELECT " + AGG + " FROM (" + base(f, p, EnumSet.noneOf(Dim.class)) + ") s" + bucketWhere(f, p);
        return jdbc.queryForObject(sql, p, (rs, i) -> money(rs));
    }

    /** Rows matching (cancelled included when the status filter lets them in): the length of the list. */
    public long count(Filter f) {
        if (f.outletIds().isEmpty()) {
            return 0;
        }
        MapSqlParameterSource p = new MapSqlParameterSource();
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM (" + base(f, p, EnumSet.noneOf(Dim.class)) + ") s" + bucketWhere(f, p), p, Long.class);
        return n == null ? 0 : n;
    }

    /** Per outlet, every outlet the caller may see whatever the outlet filter says; biggest first. */
    public List<Group> byOutlet(Filter f) {
        Collection<String> all = f.allOutletIds() == null ? f.outletIds() : f.allOutletIds();
        if (all.isEmpty()) {
            return List.of();
        }
        Filter wide = new Filter(f.hotelId(), all, f.from(), f.to(), f.buckets(), f.method(), f.attendant(), f.search(), all);
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = "SELECT s.outlet_id AS k, MAX(s.outlet_name) AS n, " + AGG + " FROM (" + base(wide, p, EnumSet.of(Dim.OUTLET)) + ") s"
                + bucketWhere(f, p) + " GROUP BY s.outlet_id ORDER BY sold DESC, n";
        return jdbc.query(sql, p, (rs, i) -> new Group(rs.getString("k"), rs.getString("n"), money(rs)));
    }

    /** Per attendant (who opened the sale), whatever the attendant filter says; biggest first. */
    public List<Group> byAttendant(Filter f) {
        if (f.outletIds().isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = "SELECT COALESCE(s.opened_by, '') AS k, MAX(s.opened_by_name) AS n, " + AGG + " FROM ("
                + base(f, p, EnumSet.of(Dim.ATTENDANT)) + ") s" + bucketWhere(f, p) + " GROUP BY COALESCE(s.opened_by, '') ORDER BY sold DESC, n";
        return jdbc.query(sql, p, (rs, i) -> new Group(rs.getString("k"), rs.getString("n"), money(rs)));
    }

    /** Sales opened before the range that still owe money (to pay or part paid), at the same outlets and attendant. */
    public Earlier earlierUnpaid(Filter f) {
        if (f.outletIds().isEmpty() || f.from() == null) {
            return new Earlier(0, BigDecimal.ZERO, null);
        }
        Filter before = new Filter(f.hotelId(), f.outletIds(), null, f.from(), SaleBucket.UNPAID, null, f.attendant(), null, null);
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = "SELECT COUNT(*) AS c, COALESCE(SUM(s.unpaid_part), 0) AS a, MIN(s.created_at) AS oldest FROM ("
                + base(before, p, EnumSet.noneOf(Dim.class)) + ") s" + bucketWhere(before, p);
        return jdbc.queryForObject(sql, p, (rs, i) -> {
            LocalDateTime oldest = rs.getObject("oldest", LocalDateTime.class);
            return new Earlier(rs.getLong("c"), rs.getBigDecimal("a"), oldest == null ? null : oldest.toInstant(ZoneOffset.UTC));
        });
    }

    // ---- SQL ---------------------------------------------------------------------------------------------

    private static final String AGG = "COUNT(*) FILTER (WHERE s.bucket <> 'CANCELLED') AS sales,"
            + " COALESCE(SUM(s.total) FILTER (WHERE s.bucket <> 'CANCELLED'), 0) AS sold,"
            + " COALESCE(SUM(s.paid_part), 0) AS paid, COALESCE(SUM(s.unpaid_part), 0) AS unpaid, COALESCE(SUM(s.room_part), 0) AS on_rooms,"
            + " COUNT(*) FILTER (WHERE s.bucket = 'CANCELLED') AS cancelled,"
            + " COALESCE(SUM(s.total) FILTER (WHERE s.bucket = 'CANCELLED'), 0) AS cancelled_amount";

    private static Money3 money(ResultSet rs) throws SQLException {
        return new Money3(rs.getLong("sales"), rs.getBigDecimal("sold"), rs.getBigDecimal("paid"), rs.getBigDecimal("unpaid"),
                rs.getBigDecimal("on_rooms"), rs.getLong("cancelled"), rs.getBigDecimal("cancelled_amount"));
    }

    /** One row per order that passes every filter but the status (and the dimensions in {@code skip}), with its bucket and money parts. */
    String base(Filter f, MapSqlParameterSource p, Set<Dim> skip) {
        StringBuilder sql = new StringBuilder("SELECT o.id, o.number, o.outlet_id, ou.name AS outlet_name, o.opened_by, o.opened_by_name, o.created_at, o.total, ")
                .append(SaleBucket.SQL).append(" AS bucket, ")
                .append(SaleBucket.PAID_PART_SQL).append(" AS paid_part, ")
                .append(SaleBucket.ROOM_PART_SQL).append(" AS room_part, ")
                .append(SaleBucket.UNPAID_PART_SQL).append(" AS unpaid_part")
                .append(" FROM fnb.orders o JOIN fnb.outlets ou ON ou.id = o.outlet_id")
                .append(" WHERE o.hotel_id = :hotelId AND o.outlet_id IN (:outletIds)");
        p.addValue("hotelId", f.hotelId()).addValue("outletIds", new ArrayList<>(f.outletIds()));
        if (f.from() != null) {
            sql.append(" AND o.created_at >= :from");
            p.addValue("from", utc(f.from()));
        }
        if (f.to() != null) {
            sql.append(" AND o.created_at < :to");
            p.addValue("to", utc(f.to()));
        }
        if (f.attendant() != null && !skip.contains(Dim.ATTENDANT)) {
            if (f.attendant().isEmpty()) {
                sql.append(" AND o.opened_by IS NULL");
            } else {
                sql.append(" AND o.opened_by = :attendant");
                p.addValue("attendant", f.attendant());
            }
        }
        if (f.method() != null) {
            if (f.method().equals("ROOM")) {
                sql.append(" AND o.charged_to_room");
            } else {
                sql.append(" AND EXISTS (SELECT 1 FROM fnb.order_payments op WHERE op.order_id = o.id AND op.method = :method)");
                p.addValue("method", f.method());
            }
        }
        if (f.search() != null && !f.search().isBlank()) {
            sql.append(" AND (o.number ILIKE :q ESCAPE '\\' OR o.guest_name ILIKE :q ESCAPE '\\'")
                    .append(" OR EXISTS (SELECT 1 FROM fnb.order_lines ol WHERE ol.order_id = o.id AND ol.name ILIKE :q ESCAPE '\\'))");
            p.addValue("q", "%" + like(f.search().trim()) + "%");
        }
        return sql.toString();
    }

    private static String bucketWhere(Filter f, MapSqlParameterSource p) {
        if (f.buckets() == null || f.buckets().size() == SaleBucket.values().length) {
            return "";
        }
        if (f.buckets().isEmpty()) {
            return " WHERE FALSE";
        }
        p.addValue("buckets", f.buckets().stream().map(Enum::name).toList());
        return " WHERE s.bucket IN (:buckets)";
    }

    /** Escapes LIKE's wildcards so "50%" finds "50%", not everything. */
    static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").toLowerCase(Locale.ROOT);
    }

    private static LocalDateTime utc(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}

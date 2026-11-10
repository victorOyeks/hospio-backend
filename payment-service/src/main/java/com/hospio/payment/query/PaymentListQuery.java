package com.hospio.payment.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The payments list in SQL (docs/pagination.md): the page of ids, the count, per-status counts without the status filter and the money,
 * all over the same filters. Timestamps are stored as UTC wall time (as Hibernate writes them), so instants are bound as UTC
 * {@link LocalDateTime}.
 */
@Component
public class PaymentListQuery {

    /** Who may be seen and what was asked. {@code businessId} is always set; the rest narrow. Instants: from inclusive, to exclusive. */
    public record Filter(String businessId, String hotelId, String requestedBy, String q, String status, String method, String role,
                         Instant from, Instant to) {
    }

    /** Sortable columns by the name the screens send. */
    public static final Map<String, String> SORTS = Map.of("createdAt", "created_at", "amount", "amount", "guestName", "guest_name",
            "status", "status", "method", "method");

    /** A Lagos day or month as instants: from inclusive, to exclusive. */
    public record Window(Instant from, Instant to) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public PaymentListQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The ids on one page, in order: {@code sortColumn} (one of {@link #SORTS}' values) then newest first, then id. */
    public List<String> ids(Filter f, String sortColumn, boolean asc, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = where(f, p, true);
        String order = sortColumn == null || sortColumn.equals("created_at") ? "created_at " + (asc ? "asc" : "desc")
                : sortColumn + (asc ? " asc nulls last" : " desc nulls last") + ", created_at desc";
        p.addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.queryForList("select id from payment_requests" + where + " order by " + order + ", id desc limit :limit offset :offset",
                p, String.class);
    }

    public long count(Filter f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        Long n = jdbc.queryForObject("select count(*) from payment_requests" + where(f, p, true), p, Long.class);
        return n == null ? 0 : n;
    }

    /** Rows per status over every filter but the status (the tabs), plus {@code all}. */
    public Map<String, Long> countsByStatus(Filter f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        Map<String, Long> out = new LinkedHashMap<>();
        long[] all = {0};
        jdbc.query("select status, count(*) c from payment_requests" + where(f, p, false) + " group by status", p, rs -> {
            out.put(rs.getString("status"), rs.getLong("c"));
            all[0] += rs.getLong("c");
        });
        out.put("all", all[0]);
        return out;
    }

    /** Approved money over every filter but the status: {@code approved}, {@code approvedCount}. */
    public Map<String, BigDecimal> approved(Filter f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = where(f, p, false);
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        jdbc.query("select coalesce(sum(amount) filter (where status = 'APPROVED'), 0) a, count(*) filter (where status = 'APPROVED') c "
                + "from payment_requests" + where, p, rs -> {
                    out.put("approved", rs.getBigDecimal("a"));
                    out.put("approvedCount", BigDecimal.valueOf(rs.getLong("c")));
                });
        return out;
    }

    /**
     * Approved money in the scope only ({@code scope}: business, hotel, taker, role; no search, method, status or dates), for the
     * cards above a list: today and this month, in all and per method ({@code todayCash}, {@code monthCard}, ...).
     */
    public Map<String, BigDecimal> period(Filter scope, Window today, Window month) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = where(scope, p, false);
        p.addValue("tf", utc(today.from())).addValue("tt", utc(today.to())).addValue("mf", utc(month.from())).addValue("mt", utc(month.to()));
        StringBuilder sql = new StringBuilder("select ");
        String[][] periods = {{"today", ":tf", ":tt"}, {"month", ":mf", ":mt"}};
        String[][] methods = {{"", null}, {"Card", "CARD"}, {"Transfer", "TRANSFER"}, {"Cash", "CASH"}};
        List<String> cols = new ArrayList<>();
        for (String[] per : periods) {
            String in = "status = 'APPROVED' and created_at >= " + per[1] + " and created_at < " + per[2];
            for (String[] m : methods) {
                String cond = m[1] == null ? in : in + " and method = '" + m[1] + "'";
                String name = m[1] == null ? "approved" + cap(per[0]) : per[0] + m[0];
                cols.add("coalesce(sum(amount) filter (where " + cond + "), 0) \"" + name + "\"");
            }
            cols.add("count(*) filter (where " + in + ") \"approved" + cap(per[0]) + "Count\"");
        }
        sql.append(String.join(", ", cols)).append(" from payment_requests").append(where);
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        jdbc.query(sql.toString(), p, rs -> {
            var md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                out.put(md.getColumnLabel(i), rs.getBigDecimal(i));
            }
        });
        return out;
    }

    /** Rows in a narrower slice of the same scope (the tab counts that are not statuses: today, taken by me). */
    public long countWhere(Filter f, Window window, String requestedBy) {
        Filter g = new Filter(f.businessId(), f.hotelId(), requestedBy != null ? requestedBy : f.requestedBy(), f.q(), null, f.method(), f.role(),
                window != null ? window.from() : f.from(), window != null ? window.to() : f.to());
        return count(g);
    }

    private static String cap(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String where(Filter f, MapSqlParameterSource p, boolean withStatus) {
        List<String> w = new ArrayList<>();
        w.add("business_id = :biz");
        p.addValue("biz", f.businessId());
        if (f.hotelId() != null) {
            w.add("hotel_id = :hotel");
            p.addValue("hotel", f.hotelId());
        }
        if (f.requestedBy() != null) {
            w.add("requested_by = :by");
            p.addValue("by", f.requestedBy());
        }
        if (f.from() != null) {
            w.add("created_at >= :from");
            p.addValue("from", utc(f.from()));
        }
        if (f.to() != null) {
            w.add("created_at < :to");
            p.addValue("to", utc(f.to()));
        }
        if (f.method() != null) {
            w.add("method = :method");
            p.addValue("method", f.method());
        }
        if (f.role() != null) {
            w.add("role = :role");
            p.addValue("role", f.role());
        }
        if (withStatus && f.status() != null) {
            // One status or several, comma separated (the "to verify" tab is PENDING,PROCESSING,NO_RESPONSE).
            w.add("status in (:status)");
            p.addValue("status", List.of(f.status().split(",")));
        }
        if (f.q() != null && !f.q().isBlank()) {
            String q = f.q().trim().toLowerCase(Locale.ROOT);
            // "PAY-1A2B3C" is how the screens show a payment: the first six characters of its id.
            String ref = q.startsWith("pay-") ? q.substring(4) : q;
            w.add("(lower(coalesce(guest_name, '')) like :q escape '\\' or lower(coalesce(description, '')) like :q escape '\\' "
                    + "or lower(coalesce(provider_ref, '')) like :q escape '\\' or lower(coalesce(requested_by_name, '')) like :q escape '\\' "
                    + "or lower(coalesce(guest_phone, '')) like :q escape '\\' or lower(id) like :ref escape '\\')");
            p.addValue("q", com.hospio.common.paging.Paging.like(q));
            p.addValue("ref", com.hospio.common.paging.Paging.like(ref).substring(1));
        }
        return " where " + String.join(" and ", w);
    }

    private static LocalDateTime utc(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}

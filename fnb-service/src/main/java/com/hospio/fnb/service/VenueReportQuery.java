package com.hospio.fnb.service;

import com.hospio.fnb.dto.VenueReport;
import com.hospio.fnb.dto.VenueToday;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The venue reports in SQL (docs/plan-venue-type.md phase 2). Every query is one grouped statement over the sales opened in
 * [{@code from}, {@code to}) at the given outlets of one location (fnb.orders by hotel and creation time, then lines and payments by
 * order), so a report is a handful of statements whatever the number of sales. Timestamps are UTC wall time, as Hibernate writes them.
 * Sales totals, per outlet and per attendant come from {@link SalesHistoryQuery} (the same money split as the sales history).
 */
@Component
@RequiredArgsConstructor
public class VenueReportQuery {

    /** A period at some outlets of one location. */
    public record Scope(String hotelId, Collection<String> outletIds, Instant from, Instant to) {
    }

    public record Extras(BigDecimal discounts, BigDecimal serviceCharge, BigDecimal tax, long complimentary, BigDecimal complimentaryAmount) {
    }

    public record VoidTotal(String byId, String by, long count, BigDecimal amount) {
    }

    public record ItemTotals(long quantity, BigDecimal revenue) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    private static final String ORDERS_IN = "o.hotel_id = :hotelId AND o.outlet_id IN (:outletIds) AND o.created_at >= :from AND o.created_at < :to";

    private static MapSqlParameterSource params(Scope s) {
        return new MapSqlParameterSource().addValue("hotelId", s.hotelId()).addValue("outletIds", new ArrayList<>(s.outletIds()))
                .addValue("from", utc(s.from())).addValue("to", utc(s.to()));
    }

    /** Discounts, service charge and tax inside the sales not cancelled; complimentary sales (closed with nothing to pay) and their worth. */
    public Extras extras(Scope s) {
        if (s.outletIds().isEmpty()) {
            return new Extras(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO);
        }
        String sql = "SELECT COALESCE(SUM(o.discount), 0) AS d, COALESCE(SUM(o.service_charge), 0) AS sc, COALESCE(SUM(o.tax), 0) AS t,"
                + " COUNT(*) FILTER (WHERE o.settled_by = 'COMPLIMENTARY') AS comp, COALESCE(SUM(o.total + o.discount) FILTER (WHERE o.settled_by = 'COMPLIMENTARY'), 0) AS comp_amount"
                + " FROM fnb.orders o WHERE " + ORDERS_IN + " AND o.status <> 'CANCELLED'";
        return jdbc.queryForObject(sql, params(s), (rs, i) -> new Extras(rs.getBigDecimal("d"), rs.getBigDecimal("sc"), rs.getBigDecimal("t"),
                rs.getLong("comp"), rs.getBigDecimal("comp_amount")));
    }

    /**
     * Items sold on sales not cancelled (live lines only), biggest revenue first, or most sold first when {@code byQuantity}; at most
     * {@code limit} rows (0: all). Revenue is the price times the quantity less the line's discount.
     */
    public List<VenueReport.ItemRow> items(Scope s, boolean byQuantity, int limit) {
        if (s.outletIds().isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource p = params(s);
        String order = byQuantity ? "qty DESC, revenue DESC, name" : "revenue DESC, qty DESC, name";
        String sql = "SELECT l.menu_item_id AS id, MAX(l.name) AS name, MAX(mi.category) AS category, MAX(o.outlet_id) AS outlet_id,"
                + " MAX(ou.name) AS outlet_name, SUM(l.quantity) AS qty, COALESCE(SUM(l.unit_price * l.quantity - l.discount), 0) AS revenue"
                + " FROM fnb.orders o JOIN fnb.order_lines l ON l.order_id = o.id JOIN fnb.outlets ou ON ou.id = o.outlet_id"
                + " LEFT JOIN fnb.menu_items mi ON mi.id = l.menu_item_id"
                + " WHERE " + ORDERS_IN + " AND o.status <> 'CANCELLED' AND l.status <> 'CANCELLED'"
                + " GROUP BY l.menu_item_id ORDER BY " + order + (limit > 0 ? " LIMIT :limit" : "");
        if (limit > 0) {
            p.addValue("limit", limit);
        }
        return jdbc.query(sql, p, (rs, i) -> {
            String cat = rs.getString("category");
            return new VenueReport.ItemRow(rs.getString("id"), rs.getString("name"), cat == null || cat.isBlank() ? "Other" : cat.trim(),
                    rs.getString("outlet_id"), rs.getString("outlet_name"), rs.getLong("qty"), rs.getBigDecimal("revenue"));
        });
    }

    /** Units and item revenue over every live line of the sales not cancelled (the items list's sums, without its limit). */
    public ItemTotals itemTotals(Scope s) {
        if (s.outletIds().isEmpty()) {
            return new ItemTotals(0, BigDecimal.ZERO);
        }
        String sql = "SELECT COALESCE(SUM(l.quantity), 0) AS qty, COALESCE(SUM(l.unit_price * l.quantity - l.discount), 0) AS revenue"
                + " FROM fnb.orders o JOIN fnb.order_lines l ON l.order_id = o.id"
                + " WHERE " + ORDERS_IN + " AND o.status <> 'CANCELLED' AND l.status <> 'CANCELLED'";
        return jdbc.queryForObject(sql, params(s), (rs, i) -> new ItemTotals(rs.getLong("qty"), rs.getBigDecimal("revenue")));
    }

    /** Sales not cancelled by the Lagos hour they were opened in (only hours with sales). */
    public List<VenueReport.HourRow> hours(Scope s) {
        if (s.outletIds().isEmpty()) {
            return List.of();
        }
        String sql = "SELECT CAST(EXTRACT(HOUR FROM (o.created_at AT TIME ZONE 'UTC') AT TIME ZONE 'Africa/Lagos') AS INTEGER) AS h,"
                + " COUNT(*) AS sales, COALESCE(SUM(o.total), 0) AS sold FROM fnb.orders o"
                + " WHERE " + ORDERS_IN + " AND o.status <> 'CANCELLED' GROUP BY 1 ORDER BY 1";
        return jdbc.query(sql, params(s), (rs, i) -> new VenueReport.HourRow(rs.getInt("h"), rs.getLong("sales"), rs.getBigDecimal("sold")));
    }

    /** Money taken at these outlets in the period (by when it was taken), per method. */
    public List<VenueReport.MethodRow> taken(Scope s) {
        if (s.outletIds().isEmpty()) {
            return List.of();
        }
        String sql = "SELECT op.method AS m, COUNT(*) AS n, COALESCE(SUM(op.amount), 0) AS a FROM fnb.order_payments op"
                + " JOIN fnb.orders o ON o.id = op.order_id"
                + " WHERE op.hotel_id = :hotelId AND o.outlet_id IN (:outletIds) AND op.created_at >= :from AND op.created_at < :to"
                + " GROUP BY op.method ORDER BY a DESC, m";
        return jdbc.query(sql, params(s), (rs, i) -> new VenueReport.MethodRow(rs.getString("m"), rs.getLong("n"), rs.getBigDecimal("a")));
    }

    /**
     * Voids: whole sales cancelled (what was on them: every line) and items taken off sales that went on. {@code by} is who cancelled. The
     * same rows drive the list and the per-person totals.
     */
    private static final String VOIDS = "SELECT 'ORDER' AS kind, o.id AS order_id, o.number, ou.name AS outlet_name, COALESCE(o.closed_at, o.updated_at) AS at,"
            + " CAST(NULL AS VARCHAR) AS item, COALESCE(x.qty, 0) AS qty, COALESCE(x.amount, o.total) AS amount, o.cancel_reason AS reason,"
            + " o.closed_by AS by_id, o.closed_by_name AS by_name"
            + " FROM fnb.orders o JOIN fnb.outlets ou ON ou.id = o.outlet_id"
            + " LEFT JOIN (SELECT l.order_id, SUM(l.quantity) AS qty, SUM(l.unit_price * l.quantity - l.discount) AS amount FROM fnb.order_lines l"
            + " JOIN fnb.orders o ON o.id = l.order_id WHERE " + ORDERS_IN + " AND o.status = 'CANCELLED' GROUP BY l.order_id) x ON x.order_id = o.id"
            + " WHERE " + ORDERS_IN + " AND o.status = 'CANCELLED'"
            + " UNION ALL"
            + " SELECT 'ITEM', o.id, o.number, ou.name, l.updated_at, l.name, l.quantity, l.unit_price * l.quantity - l.discount, l.cancel_reason,"
            + " l.cancelled_by, l.cancelled_by_name"
            + " FROM fnb.orders o JOIN fnb.order_lines l ON l.order_id = o.id JOIN fnb.outlets ou ON ou.id = o.outlet_id"
            + " WHERE " + ORDERS_IN + " AND o.status <> 'CANCELLED' AND l.status = 'CANCELLED'";

    /** The latest {@code limit} voids, newest first. */
    public List<VenueReport.VoidRow> voids(Scope s, int limit) {
        if (s.outletIds().isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource p = params(s).addValue("limit", limit);
        return jdbc.query("SELECT * FROM (" + VOIDS + ") v ORDER BY v.at DESC, v.number DESC LIMIT :limit", p, (rs, i) -> new VenueReport.VoidRow(
                rs.getString("kind"), rs.getString("order_id"), rs.getString("number"), rs.getString("outlet_name"), instant(rs, "at"),
                rs.getString("item"), rs.getLong("qty"), rs.getBigDecimal("amount"), rs.getString("reason"), rs.getString("by_id"),
                rs.getString("by_name")));
    }

    /** Voids per person who cancelled ({@code byId} empty when not recorded), most first; a null key row carries the overall totals. */
    public List<VoidTotal> voidTotals(Scope s) {
        if (s.outletIds().isEmpty()) {
            return List.of();
        }
        String sql = "SELECT COALESCE(v.by_id, '') AS k, MAX(v.by_name) AS n, COUNT(*) AS c, COALESCE(SUM(v.amount), 0) AS a FROM (" + VOIDS + ") v"
                + " GROUP BY COALESCE(v.by_id, '') ORDER BY c DESC, a DESC";
        return jdbc.query(sql, params(s), (rs, i) -> new VoidTotal(rs.getString("k"), rs.getString("n"), rs.getLong("c"), rs.getBigDecimal("a")));
    }

    /** Sales with a discount or closed as complimentary, newest first, at most {@code limit}. */
    public List<VenueReport.DiscountRow> discounts(Scope s, int limit) {
        if (s.outletIds().isEmpty()) {
            return List.of();
        }
        // What was given by hand (line discounts, complimentary items, the bill's discount) with why, by whom and who approved it;
        // the rest of o.discount is happy hour.
        String sql = "SELECT o.id, o.number, ou.name AS outlet_name, o.created_at, o.discount, o.total, (o.settled_by = 'COMPLIMENTARY') AS comp,"
                + " o.opened_by_name, COALESCE(d.manual, 0) AS manual, COALESCE(d.comp_items, 0) AS comp_items, COALESCE(d.comp_amount, 0) AS comp_amount,"
                + " NULLIF(CONCAT_WS('; ', o.discount_reason, NULLIF(d.reasons, o.discount_reason)), '') AS reasons,"
                + " NULLIF(CONCAT_WS(', ', o.discount_by_name, NULLIF(d.givers, o.discount_by_name)), '') AS givers,"
                + " NULLIF(CONCAT_WS(', ', o.discount_approved_by_name, NULLIF(d.approvers, o.discount_approved_by_name)), '') AS approvers"
                + " FROM fnb.orders o JOIN fnb.outlets ou ON ou.id = o.outlet_id"
                + " LEFT JOIN (SELECT l.order_id, SUM(l.manual_discount + l.bill_discount) AS manual, COUNT(*) FILTER (WHERE l.complimentary) AS comp_items,"
                + " SUM(l.manual_discount) FILTER (WHERE l.complimentary) AS comp_amount, STRING_AGG(DISTINCT l.discount_reason, '; ') AS reasons,"
                + " STRING_AGG(DISTINCT l.discount_by_name, ', ') AS givers, STRING_AGG(DISTINCT l.discount_approved_by_name, ', ') AS approvers"
                + " FROM fnb.order_lines l WHERE l.hotel_id = :hotelId AND l.status <> 'CANCELLED' AND (l.manual_discount > 0 OR l.bill_discount > 0)"
                + " GROUP BY l.order_id) d ON d.order_id = o.id"
                + " WHERE " + ORDERS_IN + " AND o.status <> 'CANCELLED' AND (o.discount > 0 OR o.settled_by = 'COMPLIMENTARY')"
                + " ORDER BY o.created_at DESC, o.number DESC LIMIT :limit";
        return jdbc.query(sql, params(s).addValue("limit", limit), (rs, i) -> new VenueReport.DiscountRow(rs.getString("id"), rs.getString("number"),
                rs.getString("outlet_name"), instant(rs, "created_at"), rs.getBigDecimal("discount"), rs.getBigDecimal("total"), rs.getBoolean("comp"),
                rs.getString("opened_by_name"), rs.getBigDecimal("manual"), rs.getLong("comp_items"), rs.getBigDecimal("comp_amount"),
                rs.getString("reasons"), rs.getString("givers"), rs.getString("approvers")));
    }

    /**
     * Tabs not settled at these outlets, any day (nothing paid in full, not on a room, not cancelled or closed): how many, what is still to
     * pay on them, the oldest, and those opened before {@code staleBefore} (oldest first, at most {@code limit}).
     */
    public VenueToday.OpenTabs openTabs(String hotelId, Collection<String> outletIds, Instant staleBefore, int staleHours, int limit) {
        if (outletIds.isEmpty()) {
            return new VenueToday.OpenTabs(0, BigDecimal.ZERO, null, staleHours, 0, List.of());
        }
        String open = " FROM fnb.orders o JOIN fnb.outlets ou ON ou.id = o.outlet_id WHERE o.hotel_id = :hotelId AND o.outlet_id IN (:outletIds)"
                + " AND o.kind = 'TAB' AND o.status NOT IN ('CLOSED', 'CANCELLED') AND NOT o.paid AND NOT o.charged_to_room";
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("hotelId", hotelId).addValue("outletIds", new ArrayList<>(outletIds))
                .addValue("stale", utc(staleBefore)).addValue("limit", limit);
        String balance = "GREATEST(o.total - GREATEST(o.paid_amount, 0) - GREATEST(o.room_amount, 0), 0)";
        Object[] head = jdbc.queryForObject("SELECT COUNT(*) AS c, COALESCE(SUM(" + balance + "), 0) AS v, MIN(o.created_at) AS oldest,"
                + " COUNT(*) FILTER (WHERE o.created_at < :stale) AS stale" + open, p, (rs, i) -> new Object[] {rs.getLong("c"), rs.getBigDecimal("v"),
                instant(rs, "oldest"), rs.getLong("stale")});
        List<VenueToday.Tab> stale = jdbc.query("SELECT o.id, o.number, ou.name AS outlet_name, o.guest_name, o.opened_by_name, o.created_at, "
                + balance + " AS balance" + open + " AND o.created_at < :stale ORDER BY o.created_at, o.number LIMIT :limit", p,
                (rs, i) -> new VenueToday.Tab(rs.getString("id"), rs.getString("number"), rs.getString("outlet_name"), rs.getString("guest_name"),
                        rs.getString("opened_by_name"), instant(rs, "created_at"), rs.getBigDecimal("balance")));
        return new VenueToday.OpenTabs((long) head[0], (BigDecimal) head[1], (Instant) head[2], staleHours, (long) head[3], stale);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime t = rs.getObject(column, LocalDateTime.class);
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}

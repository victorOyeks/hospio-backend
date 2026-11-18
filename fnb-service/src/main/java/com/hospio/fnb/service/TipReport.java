package com.hospio.fnb.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.fnb.rules.Days;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tips taken at the outlets (docs/plan-venue-type.md, phase 4), by who served and by Lagos day. A tip is never a sale: it is not in
 * any sales figure or in the base of Hospio360's fee; this report is the only place it is counted.
 */
@Service
@RequiredArgsConstructor
public class TipReport {

    public record Report(LocalDate from, LocalDate to, long count, BigDecimal total, BigDecimal cash, BigDecimal card, BigDecimal transfer,
                         List<StaffRow> byStaff, List<DayRow> byDay, List<TipRow> tips, boolean truncated) {
    }

    public record StaffRow(String staffId, String name, long count, BigDecimal total, BigDecimal cash, BigDecimal other) {
    }

    public record DayRow(LocalDate date, long count, BigDecimal total, BigDecimal cash, BigDecimal other) {
    }

    public record TipRow(String orderId, String number, String outletName, Instant at, String staffName, String method, BigDecimal amount,
                         String takenByName) {
    }

    static final int LIMIT = 500;

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public Report report(LocalDate from, LocalDate to, String staffId) {
        String hotelId = TenantContext.requireHotel();
        LocalDate f = from == null ? Days.today() : from;
        LocalDate t = to == null ? f : to;
        if (t.isBefore(f)) {
            throw new BadRequestException("The last day is before the first");
        }
        if (f.plusDays(SalesHistoryService.MAX_DAYS).isBefore(t)) {
            throw new BadRequestException("Up to " + SalesHistoryService.MAX_DAYS + " days at a time");
        }
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("hotelId", hotelId)
                .addValue("from", LocalDateTime.ofInstant(Days.start(f), ZoneOffset.UTC)).addValue("to", LocalDateTime.ofInstant(Days.end(t), ZoneOffset.UTC))
                .addValue("staff", staffId).addValue("limit", LIMIT + 1);
        String where = " FROM fnb.order_tips t WHERE t.hotel_id = :hotelId AND t.created_at >= :from AND t.created_at < :to"
                + (staffId == null ? "" : " AND t.staff_id = :staff");
        String sums = "COUNT(*) AS c, COALESCE(SUM(t.amount), 0) AS total, COALESCE(SUM(t.amount) FILTER (WHERE t.method = 'CASH'), 0) AS cash,"
                + " COALESCE(SUM(t.amount) FILTER (WHERE t.method = 'CARD'), 0) AS card, COALESCE(SUM(t.amount) FILTER (WHERE t.method NOT IN ('CASH', 'CARD')), 0) AS transfer";
        Report head = jdbc.queryForObject("SELECT " + sums + where, p, (rs, i) -> new Report(f, t, rs.getLong("c"), rs.getBigDecimal("total"),
                rs.getBigDecimal("cash"), rs.getBigDecimal("card"), rs.getBigDecimal("transfer"), List.of(), List.of(), List.of(), false));
        List<StaffRow> byStaff = jdbc.query("SELECT t.staff_id, MAX(t.staff_name) AS name, " + sums + where + " GROUP BY t.staff_id ORDER BY total DESC", p,
                (rs, i) -> new StaffRow(rs.getString("staff_id"), rs.getString("name"), rs.getLong("c"), rs.getBigDecimal("total"), rs.getBigDecimal("cash"),
                        rs.getBigDecimal("card").add(rs.getBigDecimal("transfer"))));
        // The Lagos day of each tip (timestamps are UTC wall time, Lagos is UTC+1 all year).
        List<DayRow> byDay = jdbc.query("SELECT (t.created_at + INTERVAL '1 hour')::date AS d, " + sums + where + " GROUP BY d ORDER BY d", p,
                (rs, i) -> new DayRow(rs.getObject("d", LocalDate.class), rs.getLong("c"), rs.getBigDecimal("total"), rs.getBigDecimal("cash"),
                        rs.getBigDecimal("card").add(rs.getBigDecimal("transfer"))));
        List<TipRow> rows = jdbc.query("SELECT t.order_id, o.number, ou.name AS outlet_name, t.created_at, t.staff_name, t.method, t.amount, t.taken_by_name"
                        + " FROM fnb.order_tips t JOIN fnb.orders o ON o.id = t.order_id LEFT JOIN fnb.outlets ou ON ou.id = t.outlet_id"
                        + " WHERE t.hotel_id = :hotelId AND t.created_at >= :from AND t.created_at < :to" + (staffId == null ? "" : " AND t.staff_id = :staff")
                        + " ORDER BY t.created_at DESC LIMIT :limit", p,
                (rs, i) -> new TipRow(rs.getString("order_id"), rs.getString("number"), rs.getString("outlet_name"),
                        rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getString("staff_name"), rs.getString("method"),
                        rs.getBigDecimal("amount"), rs.getString("taken_by_name")));
        boolean truncated = rows.size() > LIMIT;
        return new Report(f, t, head.count(), head.total(), head.cash(), head.card(), head.transfer(), byStaff, byDay,
                truncated ? rows.subList(0, LIMIT) : rows, truncated);
    }
}

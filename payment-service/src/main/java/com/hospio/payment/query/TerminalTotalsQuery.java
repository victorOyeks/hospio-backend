package com.hospio.payment.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Approved payments for outlet sales (restaurant, bar, lounge: the ones with an order) at one location, per method and POS terminal, in one
 * grouped statement (docs/plan-venue-type.md phase 2, "Payments by method and POS"). Cash and transfers have no terminal. Timestamps are
 * UTC wall time, as Hibernate writes them.
 */
@Component
public class TerminalTotalsQuery {

    /** {@code terminalId} and {@code terminal} are null for payments not taken on a POS (cash, transfer, or a card keyed elsewhere). */
    public record Row(String method, String terminalId, String terminal, String provider, long count, BigDecimal amount) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public TerminalTotalsQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> outletPayments(String hotelId, Instant from, Instant to) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("hotelId", hotelId)
                .addValue("from", LocalDateTime.ofInstant(from, ZoneOffset.UTC)).addValue("to", LocalDateTime.ofInstant(to, ZoneOffset.UTC));
        String sql = "select pr.method, pr.terminal_id, max(t.name) as terminal, max(coalesce(t.provider, pr.provider)) as provider, count(*) as n,"
                + " coalesce(sum(pr.amount), 0) as amount from payment_requests pr left join pos_terminals t on t.id = pr.terminal_id"
                + " where pr.hotel_id = :hotelId and pr.order_id is not null and pr.status = 'APPROVED'"
                + " and pr.created_at >= :from and pr.created_at < :to"
                + " group by pr.method, pr.terminal_id order by amount desc, pr.method";
        return jdbc.query(sql, p, (rs, i) -> new Row(rs.getString("method"), rs.getString("terminal_id"), rs.getString("terminal"),
                rs.getString("provider"), rs.getLong("n"), rs.getBigDecimal("amount")));
    }
}

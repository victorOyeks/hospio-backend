package com.hospio.reservation.query;

import com.hospio.common.paging.Paging;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The bookings list in SQL (docs/pagination.md): a page of ids, the count, the tab counts (the views every bookings screen shows) and the
 * money over the scope. Views use the hotel's day ({@code today}): arrivals, departures, in-house, upcoming, past, all.
 */
@Component
public class BookingListQuery {

    /** {@code from}/{@code to}: stays that touch those days (both inclusive); {@code arrivingFrom}/{@code arrivingTo}: by check-in day. */
    public record Filter(String businessId, String hotelId, String q, String status, String view, LocalDate from, LocalDate to,
                         LocalDate arrivingFrom, LocalDate arrivingTo, String guestId, LocalDate today) {

        public Filter withoutTabs() {
            return new Filter(businessId, hotelId, q, null, null, from, to, arrivingFrom, arrivingTo, guestId, today);
        }

        public Filter scopeOnly() {
            return new Filter(businessId, hotelId, null, null, null, null, null, null, null, null, today);
        }
    }

    public static final List<String> VIEWS = List.of("arrivals", "departures", "in-house", "upcoming", "past", "all");

    public static final Map<String, String> SORTS = Map.of("checkInDate", "check_in_date", "checkOutDate", "check_out_date", "guestName",
            "guest_name", "totalAmount", "total_amount", "createdAt", "created_at", "status", "status", "roomNumber", "room_number");

    private final NamedParameterJdbcTemplate jdbc;

    public BookingListQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The ids of one page: {@code sortColumn} first when given, then the latest stay first, the booking made last first, then id. */
    public List<String> ids(Filter f, String sortColumn, boolean asc, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = where(f, p, true);
        String order = (sortColumn == null ? "" : sortColumn + (asc ? " asc nulls last, " : " desc nulls last, "))
                + "check_in_date desc, created_at desc, id desc";
        p.addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.queryForList("select id from bookings b" + where + " order by " + order + " limit :limit offset :offset", p, String.class);
    }

    public long count(Filter f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        Long n = jdbc.queryForObject("select count(*) from bookings b" + where(f, p, true), p, Long.class);
        return n == null ? 0 : n;
    }

    /** Rows per view and per status over every filter but the view and the status. */
    public Map<String, Long> counts(Filter f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = where(f.withoutTabs(), p, true);
        p.addValue("today", f.today());
        List<String> cols = new ArrayList<>();
        for (String v : VIEWS) {
            cols.add("count(*) filter (where " + view(v) + ") \"" + v + "\"");
        }
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("select " + String.join(", ", cols) + " from bookings b" + where, p, rs -> {
            for (String v : VIEWS) {
                out.put(v, rs.getLong(v));
            }
        });
        MapSqlParameterSource p2 = new MapSqlParameterSource();
        jdbc.query("select status, count(*) c from bookings b" + where(f.withoutTabs(), p2, true) + " group by status", p2,
                rs -> {
                    out.put(rs.getString("status"), rs.getLong("c"));
                });
        return out;
    }

    /**
     * The cards above the list over the scope only (business or hotel): what is booked and not cancelled, what is still unpaid (company
     * stays aside), and today's arrivals split into checked in and still to arrive.
     */
    public Map<String, BigDecimal> totals(Filter f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = where(f.scopeOnly(), p, true);
        p.addValue("today", f.today());
        p.addValue("ms", f.today().withDayOfMonth(1)).addValue("me", f.today().withDayOfMonth(f.today().lengthOfMonth()));
        String live = "status not in ('CANCELLED', 'NO_SHOW')";
        String owes = "payment_plan <> 'ORGANIZATION' and total_amount - amount_paid > 0";
        String sql = "select coalesce(sum(total_amount) filter (where " + live + " and price_kind <> 'COMPLIMENTARY'), 0) \"booked\", "
                + "count(*) filter (where " + live + ") \"bookedCount\", "
                + "coalesce(sum(greatest(total_amount - amount_paid, 0)) filter (where " + live + " and payment_plan <> 'ORGANIZATION'), 0) \"unpaid\", "
                + "count(*) filter (where " + live + " and payment_plan <> 'ORGANIZATION' and total_amount - amount_paid > 0) \"unpaidCount\", "
                + "count(*) filter (where " + view("arrivals") + " and status = 'CHECKED_IN') \"arrivalsCheckedIn\", "
                + "count(*) filter (where " + view("arrivals") + " and status = 'BOOKED') \"arrivalsToCome\", "
                + "coalesce(sum(amount_paid) filter (where status <> 'CANCELLED' and check_in_date <= :me and check_out_date >= :ms), 0) \"paidThisMonth\", "
                + "coalesce(sum(total_amount - amount_paid) filter (where status = 'CHECKED_IN' and " + owes + "), 0) \"inHouseUnpaid\", "
                + "count(*) filter (where status = 'CHECKED_IN' and " + owes + ") \"inHouseUnpaidCount\" from bookings b" + where;
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        jdbc.query(sql, p, rs -> {
            var md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                out.put(md.getColumnLabel(i), rs.getBigDecimal(i));
            }
        });
        return out;
    }

    /** The view as SQL over {@code :today} (the same rules the screens used on the whole list). */
    static String view(String v) {
        return switch (v) {
            case "arrivals" -> "(b.check_in_date = :today and b.status not in ('CANCELLED', 'CHECKED_OUT'))";
            case "departures" -> "(b.check_out_date = :today and b.status = 'CHECKED_IN')";
            case "in-house" -> "(b.status = 'CHECKED_IN')";
            case "upcoming" -> "(b.status = 'BOOKED' and b.check_in_date > :today)";
            case "past" -> "(b.status in ('CHECKED_OUT', 'CANCELLED') or (b.status = 'NO_SHOW' and b.check_in_date < :today))";
            default -> "true";
        };
    }

    private static String where(Filter f, MapSqlParameterSource p, boolean withTabs) {
        List<String> w = new ArrayList<>();
        w.add("b.business_id = :biz");
        p.addValue("biz", f.businessId());
        if (f.hotelId() != null) {
            w.add("b.hotel_id = :hotel");
            p.addValue("hotel", f.hotelId());
        }
        if (f.guestId() != null) {
            w.add("b.guest_id = :guest");
            p.addValue("guest", f.guestId());
        }
        if (f.from() != null) {
            w.add("b.check_out_date >= :from");
            p.addValue("from", f.from());
        }
        if (f.to() != null) {
            w.add("b.check_in_date <= :to");
            p.addValue("to", f.to());
        }
        if (f.arrivingFrom() != null) {
            w.add("b.check_in_date >= :af");
            p.addValue("af", f.arrivingFrom());
        }
        if (f.arrivingTo() != null) {
            w.add("b.check_in_date <= :at");
            p.addValue("at", f.arrivingTo());
        }
        if (withTabs && f.status() != null) {
            w.add("b.status in (:status)");
            p.addValue("status", List.of(f.status().split(",")));
        }
        if (withTabs && f.view() != null && !f.view().equals("all")) {
            w.add(view(f.view()));
            p.addValue("today", f.today());
        }
        if (f.q() != null && !f.q().isBlank()) {
            String q = f.q().trim().toLowerCase(Locale.ROOT);
            w.add("(lower(b.guest_name) like :q escape '\\' or lower(b.reference) like :q escape '\\' "
                    + "or lower(coalesce(b.room_number, '')) like :q escape '\\' or lower(coalesce(b.room_type_name, '')) like :q escape '\\' "
                    + "or exists (select 1 from guests g where g.id = b.guest_id and (coalesce(g.phone, '') like :q escape '\\' "
                    + "or lower(coalesce(g.email, '')) like :q escape '\\')))");
            p.addValue("q", Paging.like(q));
        }
        return " where " + String.join(" and ", w);
    }
}

package com.hospio.reservation.query;

import com.hospio.common.paging.Paging;
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
 * The guests directory in SQL: business-wide profiles, searched by name, phone or email, with the views the screen shows (all, in house
 * at the hotel, repeat, blacklisted) and each guest's last stay.
 */
@Component
public class GuestListQuery {

    /** {@code hotelId}: where "in house" is read (null: any hotel of the business). */
    public record Filter(String businessId, String hotelId, String q, String view) {
    }

    public record Row(String id, boolean inHouse, LocalDate lastStay) {
    }

    public static final List<String> VIEWS = List.of("all", "in-house", "repeat", "blacklisted");

    public static final Map<String, String> SORTS = Map.of("fullName", "full_name", "stays", "stays", "createdAt", "created_at");

    private final NamedParameterJdbcTemplate jdbc;

    public GuestListQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One page: {@code sortColumn} first when given, then the newest profile first, then id. */
    public List<Row> page(Filter f, String sortColumn, boolean asc, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = where(f, p, true);
        String order = (sortColumn == null ? "" : "g." + sortColumn + (asc ? " asc nulls last, " : " desc nulls last, ")) + "g.created_at desc, g.id desc";
        p.addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.query("select g.id, " + inHouse(f, p) + " in_house, (select max(b.check_in_date) from bookings b where b.guest_id = g.id) last_stay "
                + "from guests g" + where + " order by " + order + " limit :limit offset :offset", p,
                (rs, i) -> new Row(rs.getString("id"), rs.getBoolean("in_house"), rs.getObject("last_stay", LocalDate.class)));
    }

    public long count(Filter f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        Long n = jdbc.queryForObject("select count(*) from guests g" + where(f, p, true), p, Long.class);
        return n == null ? 0 : n;
    }

    /** Rows per view over the search. */
    public Map<String, Long> counts(Filter f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = where(new Filter(f.businessId(), f.hotelId(), f.q(), null), p, true);
        List<String> cols = new ArrayList<>();
        for (String v : VIEWS) {
            cols.add("count(*) filter (where " + view(v, f, p) + ") \"" + v + "\"");
        }
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("select " + String.join(", ", cols) + " from guests g" + where, p, rs -> {
            for (String v : VIEWS) {
                out.put(v, rs.getLong(v));
            }
        });
        return out;
    }

    private static String inHouse(Filter f, MapSqlParameterSource p) {
        if (f.hotelId() != null) {
            p.addValue("ihotel", f.hotelId());
            return "exists (select 1 from bookings b where b.guest_id = g.id and b.status = 'CHECKED_IN' and b.hotel_id = :ihotel)";
        }
        return "exists (select 1 from bookings b where b.guest_id = g.id and b.status = 'CHECKED_IN')";
    }

    private static String view(String v, Filter f, MapSqlParameterSource p) {
        return switch (v) {
            case "in-house" -> inHouse(f, p);
            case "repeat" -> "g.stays > 1";
            case "blacklisted" -> "g.blacklisted";
            default -> "true";
        };
    }

    private static String where(Filter f, MapSqlParameterSource p, boolean withView) {
        List<String> w = new ArrayList<>();
        w.add("g.business_id = :biz");
        p.addValue("biz", f.businessId());
        if (withView && f.view() != null && !f.view().equals("all")) {
            w.add(view(f.view(), f, p));
        }
        if (f.q() != null && !f.q().isBlank()) {
            // A number is matched on its digits too, so "0803 111 0001" finds "+234 803 111 0001".
            String digits = f.q().replaceAll("[^0-9]", "");
            String byDigits = digits.length() >= 6
                    ? " or regexp_replace(coalesce(g.phone, ''), '[^0-9]', '', 'g') like :qd"
                    : "";
            w.add("(lower(g.full_name) like :q escape '\\' or coalesce(g.phone, '') like :q escape '\\' or lower(coalesce(g.email, '')) like :q escape '\\'"
                    + byDigits + ")");
            p.addValue("q", Paging.like(f.q().trim().toLowerCase(Locale.ROOT)));
            if (!byDigits.isEmpty()) {
                // From the 10th-last digit: "08031110001" and "2348031110001" both look for "8031110001".
                p.addValue("qd", "%" + (digits.length() > 10 ? digits.substring(digits.length() - 10) : digits.replaceFirst("^0", "")) + "%");
            }
        }
        return " where " + String.join(" and ", w);
    }
}

package com.hospio.reservation.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The bookings and guests list SQL against a real Postgres with the service's Liquibase schema: pages without gaps or overlap, the view
 * counts and money over the whole range (the same on every page), search by guest phone, and the owner's sum over hotels.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReservationListQueryTest {

    static PostgreSQLContainer<?> pg;
    static JdbcTemplate jdbc;
    static BookingListQuery bookings;
    static GuestListQuery guests;
    static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    @BeforeAll
    static void start() throws Exception {
        pg = new PostgreSQLContainer<>("postgres:16-alpine");
        pg.start();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(pg.getJdbcUrl() + "&currentSchema=reservation");
        ds.setUser(pg.getUsername());
        ds.setPassword(pg.getPassword());
        new JdbcTemplate(ds).execute("CREATE SCHEMA IF NOT EXISTS reservation");
        SpringLiquibase lb = new SpringLiquibase();
        lb.setDataSource(ds);
        lb.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        lb.setDefaultSchema("reservation");
        lb.setResourceLoader(new DefaultResourceLoader());
        lb.afterPropertiesSet();
        jdbc = new JdbcTemplate(ds);
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(ds);
        bookings = new BookingListQuery(named);
        guests = new GuestListQuery(named);
        guest("g-ada", "Ada Obi", "+2348011111111", 3, false, 0);
        guest("g-bayo", "Bayo Ade", "+2348022222222", 1, true, 1);
        for (int i = 0; i < 30; i++) {
            guest("g" + String.format("%02d", i), "Guest " + i, "+23470" + String.format("%08d", i), 1, false, 10 + i);
        }
        // Hotel h1: 40 past stays (checked out), 3 arrivals today (2 booked, 1 checked in), 2 departures today, 5 upcoming, 1 cancelled.
        for (int i = 0; i < 40; i++) {
            booking("p" + String.format("%02d", i), "h1", "g" + String.format("%02d", i % 30), TODAY.minusDays(60 - i), TODAY.minusDays(58 - i),
                    "CHECKED_OUT", "10000", "10000", "PAY_IN_FULL", "NORMAL", i);
        }
        booking("a1", "h1", "g-ada", TODAY, TODAY.plusDays(2), "BOOKED", "20000", "0", "PAY_IN_FULL", "NORMAL", 100);
        booking("a2", "h1", "g-bayo", TODAY, TODAY.plusDays(1), "BOOKED", "15000", "5000", "DEPOSIT_50", "NORMAL", 101);
        booking("a3", "h1", "g01", TODAY, TODAY.plusDays(3), "CHECKED_IN", "30000", "30000", "PAY_IN_FULL", "NORMAL", 102);
        booking("d1", "h1", "g02", TODAY.minusDays(2), TODAY, "CHECKED_IN", "12000", "2000", "PAY_AT_CHECKOUT", "NORMAL", 103);
        booking("d2", "h1", "g03", TODAY.minusDays(1), TODAY, "CHECKED_IN", "8000", "0", "ORGANIZATION", "NORMAL", 104);
        for (int i = 0; i < 5; i++) {
            booking("u" + i, "h1", "g1" + i, TODAY.plusDays(5 + i), TODAY.plusDays(6 + i), "BOOKED", "9000", "0", "PAY_AT_CHECKOUT", "NORMAL", 110 + i);
        }
        booking("c1", "h1", "g20", TODAY.plusDays(1), TODAY.plusDays(2), "CANCELLED", "50000", "0", "PAY_IN_FULL", "NORMAL", 120);
        // Hotel h2 of the same business.
        booking("h2a", "h2", "g-ada", TODAY, TODAY.plusDays(1), "BOOKED", "7000", "0", "PAY_IN_FULL", "NORMAL", 130);
        booking("h2b", "h2", "g21", TODAY.minusDays(3), TODAY.plusDays(1), "CHECKED_IN", "6000", "6000", "PAY_IN_FULL", "COMPLIMENTARY", 131);
    }

    @AfterAll
    static void stop() {
        if (pg != null) {
            pg.stop();
        }
    }

    static void guest(String id, String name, String phone, int stays, boolean blacklisted, int minutes) {
        LocalDateTime at = LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(minutes);
        jdbc.update("insert into guests (id, business_id, full_name, phone, stays, blacklisted, created_at, updated_at) values (?,?,?,?,?,?,?,?)",
                id, "b1", name, phone, stays, blacklisted, at, at);
    }

    static void booking(String id, String hotel, String guestId, LocalDate in, LocalDate out, String status, String total, String paid,
                        String plan, String kind, int minutes) {
        LocalDateTime at = LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(minutes);
        String name = jdbc.queryForObject("select full_name from guests where id = ?", String.class, guestId);
        jdbc.update("insert into bookings (id, business_id, hotel_id, reference, guest_id, guest_name, room_type_id, room_type_name, room_number, "
                + "check_in_date, check_out_date, status, price_kind, nightly_rate, total_amount, amount_paid, payment_plan, created_at, updated_at) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", id, "b1", hotel, ("R-" + id).toUpperCase(), guestId, name, "t1", "Deluxe",
                "1" + minutes, in, out, status, kind, new java.math.BigDecimal(total), new java.math.BigDecimal(total), new java.math.BigDecimal(paid),
                plan, at, at);
    }

    static BookingListQuery.Filter h1(String q, String status, String view) {
        return new BookingListQuery.Filter("b1", "h1", q, status, view, null, null, null, null, null, TODAY);
    }

    @Test
    void pagesCoverEveryBookingOnceLatestStayFirst() {
        BookingListQuery.Filter f = h1(null, null, null);
        assertThat(bookings.count(f)).isEqualTo(51);
        List<String> all = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            all.addAll(bookings.ids(f, null, false, p, 25));
        }
        assertThat(all).hasSize(51);
        assertThat(new HashSet<>(all)).hasSize(51);
        assertThat(all.get(0)).isEqualTo("u4");
        Map<String, Long> counts = bookings.counts(h1(null, null, "past"));
        assertThat(counts).containsEntry("all", 51L).containsEntry("arrivals", 3L).containsEntry("departures", 2L).containsEntry("in-house", 3L)
                .containsEntry("upcoming", 5L).containsEntry("past", 41L).containsEntry("CHECKED_OUT", 40L);
        assertThat(bookings.count(h1(null, null, "past"))).isEqualTo(41);
        assertThat(bookings.count(h1(null, "BOOKED,CHECKED_IN", null))).isEqualTo(10);
    }

    @Test
    void searchReachesGuestPhonesReferencesAndRooms() {
        assertThat(bookings.count(h1("8011111111", null, null))).isEqualTo(1);
        assertThat(bookings.count(h1("r-u", null, null))).isEqualTo(5);
        assertThat(bookings.counts(h1("ada", null, "upcoming"))).containsEntry("all", 1L).containsEntry("arrivals", 1L);
    }

    @Test
    void datesNarrowByStayOrArrival() {
        BookingListQuery.Filter touching = new BookingListQuery.Filter("b1", "h1", null, null, null, TODAY, TODAY, null, null, null, TODAY);
        assertThat(bookings.count(touching)).isEqualTo(5);
        BookingListQuery.Filter arriving = new BookingListQuery.Filter("b1", "h1", null, null, null, null, null, TODAY.plusDays(1), null, null, TODAY);
        assertThat(bookings.count(arriving)).isEqualTo(6);
    }

    @Test
    void allHotelsIsTheSumOfTheHotels() {
        BookingListQuery.Filter all = new BookingListQuery.Filter("b1", null, null, null, null, null, null, null, null, null, TODAY);
        BookingListQuery.Filter h2 = new BookingListQuery.Filter("b1", "h2", null, null, null, null, null, null, null, null, TODAY);
        var t = bookings.totals(all);
        var t1 = bookings.totals(h1(null, null, null));
        var t2 = bookings.totals(h2);
        assertThat(t.get("booked")).isEqualByComparingTo(t1.get("booked").add(t2.get("booked")));
        assertThat(t.get("unpaid")).isEqualByComparingTo(t1.get("unpaid").add(t2.get("unpaid")));
        // h1: 400000 checked out + 20000 + 15000 + 30000 + 12000 + 8000 + 45000 upcoming; the cancelled stay is left out.
        assertThat(t1.get("booked")).isEqualByComparingTo("530000");
        // h1 unpaid: 20000 + 10000 + 10000 + 45000 (the company stay is billed, not owed).
        assertThat(t1.get("unpaid")).isEqualByComparingTo("85000");
        assertThat(t1.get("arrivalsCheckedIn")).isEqualByComparingTo("1");
        assertThat(t1.get("arrivalsToCome")).isEqualByComparingTo("2");
        // In house owing at h1: d1 owes 10000 (d2 is a company stay, a3 is paid).
        assertThat(t1.get("inHouseUnpaid")).isEqualByComparingTo("10000");
        assertThat(t1.get("inHouseUnpaidCount")).isEqualByComparingTo("1");
        assertThat(t1.get("paidThisMonth")).isPositive();
        // The complimentary stay at h2 is not booked value.
        assertThat(t2.get("booked")).isEqualByComparingTo("7000");
        assertThat(bookings.count(all)).isEqualTo(53);
    }

    @Test
    void guestsPageWithViewsAndLastStay() {
        GuestListQuery.Filter f = new GuestListQuery.Filter("b1", "h1", null, null);
        assertThat(guests.count(f)).isEqualTo(32);
        List<GuestListQuery.Row> p0 = guests.page(f, null, false, 0, 25);
        List<GuestListQuery.Row> p1 = guests.page(f, null, false, 1, 25);
        assertThat(p0).hasSize(25);
        assertThat(p1).hasSize(7);
        HashSet<String> ids = new HashSet<>();
        p0.forEach(r -> ids.add(r.id()));
        p1.forEach(r -> ids.add(r.id()));
        assertThat(ids).hasSize(32);
        assertThat(p0.get(0).id()).isEqualTo("g29");
        Map<String, Long> counts = guests.counts(f);
        assertThat(counts).containsEntry("all", 32L).containsEntry("in-house", 3L).containsEntry("repeat", 1L).containsEntry("blacklisted", 1L);
        assertThat(guests.count(new GuestListQuery.Filter("b1", "h1", "8022222222", null))).isEqualTo(1);
        GuestListQuery.Row ada = guests.page(new GuestListQuery.Filter("b1", "h1", "ada obi", null), null, false, 0, 25).get(0);
        assertThat(ada.lastStay()).isEqualTo(TODAY);
        assertThat(ada.inHouse()).isFalse();
        assertThat(guests.page(new GuestListQuery.Filter("b1", "h1", null, "in-house"), "full_name", true, 0, 25)).extracting(GuestListQuery.Row::id)
                .containsExactly("g01", "g02", "g03");
    }
}

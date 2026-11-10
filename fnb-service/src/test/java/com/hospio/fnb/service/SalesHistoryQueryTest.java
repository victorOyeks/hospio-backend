package com.hospio.fnb.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.fnb.rules.Days;
import com.hospio.fnb.rules.SaleBucket;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
 * The sales history SQL against a real Postgres with the service's own Liquibase schema: Lagos day edges, every filter, the money split
 * and the breakdowns adding up. Skipped where Docker is not running (CI builds without tests).
 */
@Testcontainers(disabledWithoutDocker = true)
class SalesHistoryQueryTest {

    static PostgreSQLContainer<?> pg;
    static JdbcTemplate jdbc;
    static SalesHistoryQuery query;
    static final String HOTEL = "h1";
    static final LocalDate DAY = LocalDate.of(2026, 11, 9);

    @BeforeAll
    static void start() throws Exception {
        pg = new PostgreSQLContainer<>("postgres:16-alpine");
        pg.start();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(pg.getJdbcUrl());
        ds.setUser(pg.getUsername());
        ds.setPassword(pg.getPassword());
        // The service's pool creates the schema on connect (hikari connection-init-sql); here it is done once.
        new JdbcTemplate(ds).execute("CREATE SCHEMA IF NOT EXISTS fnb");
        SpringLiquibase lb = new SpringLiquibase();
        lb.setDataSource(ds);
        lb.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        lb.setDefaultSchema("fnb");
        lb.setResourceLoader(new DefaultResourceLoader());
        lb.afterPropertiesSet();
        jdbc = new JdbcTemplate(ds);
        query = new SalesHistoryQuery(new NamedParameterJdbcTemplate(ds));
        seed();
    }

    @AfterAll
    static void stop() {
        if (pg != null) {
            pg.stop();
        }
    }

    // UTC wall times, as Hibernate stores them. Lagos is UTC+1: 2026-11-09 starts at 2026-11-08 23:00 UTC.
    static void seed() {
        outlet("bar", "Bar", "BAR", HOTEL);
        outlet("kit", "Restaurant", "KITCHEN", HOTEL);
        outlet("bar2", "Pool bar", "BAR", "h2");
        order("B1", "bar", "TAKEAWAY", "OPEN", "4500", "0", false, false, null, "u1", "Segun Ajayi", "2026-11-08T23:30:00", null);
        line("B1", "Coca-Cola", 3, "1500", "NEW");
        order("B2", "bar", "TAKEAWAY", "CLOSED", "5300", "5300", true, false, "PAYMENT", "u1", "Segun Ajayi", "2026-11-09T10:00:00", "Ada Obi");
        line("B2", "Orange juice", 2, "2650", "NEW");
        payment("B2", "CASH", "5300");
        order("B3", "bar", "TAB", "OPEN", "2000", "0", false, false, null, "u2", "Ngozi Eze", "2026-11-09T11:00:00", null);
        order("B4", "bar", "TAKEAWAY", "CLOSED", "18000", "0", false, true, "ROOM", "u2", "Ngozi Eze", "2026-11-09T12:00:00", "QA Guest");
        order("B5", "bar", "TAKEAWAY", "OPEN", "3000", "1000", false, false, null, "u1", "Segun Ajayi", "2026-11-09T13:00:00", null);
        payment("B5", "CARD", "1000");
        order("B6", "bar", "TAKEAWAY", "CANCELLED", "1500", "0", false, false, "CANCELLED", "u2", "Ngozi Eze", "2026-11-09T14:00:00", null);
        order("K1", "kit", "DINE_IN", "SENT", "3000", "0", false, false, null, "u3", "Mariam Bello", "2026-11-09T15:00:00", null);
        order("K2", "kit", "DINE_IN", "SERVED", "2500", "0", false, false, null, "u3", "Mariam Bello", "2026-11-09T16:00:00", null);
        order("B7", "bar", "TAKEAWAY", "OPEN", "1200", "0", false, false, null, "u1", "Segun Ajayi", "2026-11-09T22:59:59", null);
        line("B7", "Chapman 50% sugar", 1, "1200", "NEW");
        order("B8", "bar", "TAKEAWAY", "OPEN", "900", "0", false, false, null, "u1", "Segun Ajayi", "2026-11-09T23:00:00", null);
        order("B0", "bar", "TAKEAWAY", "OPEN", "800", "0", false, false, null, "u1", "Segun Ajayi", "2026-11-07T12:00:00", null);
        order("X1", "bar2", "TAKEAWAY", "OPEN", "999", "0", false, false, null, "u1", "Segun Ajayi", "2026-11-09T12:00:00", null);
    }

    static void outlet(String id, String name, String kind, String hotel) {
        jdbc.update("INSERT INTO fnb.outlets (id, business_id, hotel_id, name, kind, created_at, updated_at) VALUES (?, 'b1', ?, ?, ?, now(), now())",
                id, hotel, name, kind);
    }

    static void order(String number, String outlet, String kind, String status, String total, String paidAmount, boolean paid, boolean room,
                      String settledBy, String by, String byName, String createdUtc, String guest) {
        String hotel = outlet.equals("bar2") ? "h2" : HOTEL;
        jdbc.update("INSERT INTO fnb.orders (id, business_id, hotel_id, outlet_id, number, kind, status, total, paid_amount, paid, charged_to_room,"
                        + " settled_by, opened_by, opened_by_name, guest_name, created_at, updated_at) VALUES (?, 'b1', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                number, hotel, outlet, number.startsWith("K") ? "K-00" + number.substring(1) : "B-000" + number.substring(1), kind, status,
                new BigDecimal(total), new BigDecimal(paidAmount), paid, room, settledBy, by, byName, guest, Timestamp.valueOf(LocalDateTime.parse(createdUtc)),
                Timestamp.valueOf(LocalDateTime.parse(createdUtc)));
    }

    static void line(String order, String name, int qty, String price, String status) {
        jdbc.update("INSERT INTO fnb.order_lines (id, business_id, hotel_id, order_id, menu_item_id, name, quantity, unit_price, status, created_at, updated_at)"
                + " VALUES (?, 'b1', ?, ?, 'm', ?, ?, ?, ?, now(), now())", UUID.randomUUID().toString(), HOTEL, order, name, qty, new BigDecimal(price), status);
    }

    static void payment(String order, String method, String amount) {
        jdbc.update("INSERT INTO fnb.order_payments (id, business_id, hotel_id, order_id, payment_id, method, amount, created_at, updated_at)"
                + " VALUES (?, 'b1', ?, ?, ?, ?, ?, now(), now())", UUID.randomUUID().toString(), HOTEL, order, UUID.randomUUID().toString(), method,
                new BigDecimal(amount));
    }

    static SalesHistoryQuery.Filter day(LocalDate d, Set<SaleBucket> buckets, String method, String attendant, String q, List<String> outlets) {
        return new SalesHistoryQuery.Filter(HOTEL, outlets, Days.start(d), Days.end(d), buckets, method, attendant, q, List.of("bar", "kit"));
    }

    static SalesHistoryQuery.Filter day(Set<SaleBucket> buckets, String method, String attendant, String q) {
        return day(DAY, buckets, method, attendant, q, List.of("bar", "kit"));
    }

    static final Set<SaleBucket> ALL = EnumSet.allOf(SaleBucket.class);

    @Test
    void aLagosDayRunsFromLagosMidnightToLagosMidnightNewestFirst() {
        assertThat(query.pageIds(day(ALL, null, null, null), 0, 100)).containsExactly("B7", "K2", "K1", "B6", "B5", "B4", "B3", "B2", "B1");
        assertThat(query.pageIds(day(DAY.plusDays(1), ALL, null, null, null, List.of("bar", "kit")), 0, 100)).containsExactly("B8");
        assertThat(query.count(day(ALL, null, null, null))).isEqualTo(9);
    }

    @Test
    void theMoneySplitsIntoPaidOnRoomsAndUnpaidAndAddsUpToWhatWasSold() {
        SalesHistoryQuery.Money3 t = query.totals(day(ALL, null, null, null));
        assertThat(t.sales()).isEqualTo(8);
        assertThat(t.sold()).isEqualByComparingTo("39500");
        assertThat(t.paid()).isEqualByComparingTo("6300");
        assertThat(t.onRooms()).isEqualByComparingTo("18000");
        assertThat(t.unpaid()).isEqualByComparingTo("15200");
        assertThat(t.paid().add(t.onRooms()).add(t.unpaid())).isEqualByComparingTo(t.sold());
        assertThat(t.cancelled()).isEqualTo(1);
        assertThat(t.cancelledAmount()).isEqualByComparingTo("1500");
    }

    @Test
    void eachStatusIsItsOwnBucket() {
        assertThat(query.pageIds(day(EnumSet.of(SaleBucket.TO_PAY), null, null, null), 0, 100)).containsExactly("B7", "K2", "B1");
        assertThat(query.pageIds(day(SaleBucket.UNPAID, null, null, null), 0, 100)).containsExactly("B7", "K2", "B5", "B1");
        assertThat(query.pageIds(day(EnumSet.of(SaleBucket.PART_PAID), null, null, null), 0, 100)).containsExactly("B5");
        assertThat(query.pageIds(day(EnumSet.of(SaleBucket.PAID), null, null, null), 0, 100)).containsExactly("B2");
        assertThat(query.pageIds(day(EnumSet.of(SaleBucket.ON_ROOM), null, null, null), 0, 100)).containsExactly("B4");
        assertThat(query.pageIds(day(EnumSet.of(SaleBucket.CANCELLED), null, null, null), 0, 100)).containsExactly("B6");
        assertThat(query.pageIds(day(EnumSet.of(SaleBucket.IN_PROGRESS), null, null, null), 0, 100)).containsExactly("K1", "B3");
        assertThat(query.totals(day(EnumSet.of(SaleBucket.TO_PAY), null, null, null)).unpaid()).isEqualByComparingTo("8200");
    }

    @Test
    void methodsAttendantOutletAndSearchNarrowTheList() {
        assertThat(query.pageIds(day(ALL, "CASH", null, null), 0, 100)).containsExactly("B2");
        assertThat(query.pageIds(day(ALL, "CARD", null, null), 0, 100)).containsExactly("B5");
        assertThat(query.pageIds(day(ALL, "TRANSFER", null, null), 0, 100)).isEmpty();
        assertThat(query.pageIds(day(ALL, "ROOM", null, null), 0, 100)).containsExactly("B4");
        assertThat(query.pageIds(day(ALL, null, "u1", null), 0, 100)).containsExactly("B7", "B5", "B2", "B1");
        assertThat(query.pageIds(day(DAY, ALL, null, null, null, List.of("kit")), 0, 100)).containsExactly("K2", "K1");
        assertThat(query.pageIds(day(ALL, null, null, "coca"), 0, 100)).containsExactly("B1");
        assertThat(query.pageIds(day(ALL, null, null, "ADA"), 0, 100)).containsExactly("B2");
        assertThat(query.pageIds(day(ALL, null, null, "b-0004"), 0, 100)).containsExactly("B4");
        assertThat(query.pageIds(day(ALL, null, null, "50%"), 0, 100)).containsExactly("B7");
        assertThat(query.pageIds(day(ALL, null, null, "5_%"), 0, 100)).isEmpty();
    }

    @Test
    void perOutletAndPerAttendantAddUpToTheTotalsAndIgnoreTheirOwnFilter() {
        SalesHistoryQuery.Money3 t = query.totals(day(ALL, null, null, null));
        List<SalesHistoryQuery.Group> outlets = query.byOutlet(day(DAY, ALL, null, null, null, List.of("kit")));
        assertThat(outlets).extracting(SalesHistoryQuery.Group::key).containsExactly("bar", "kit");
        assertThat(sum(outlets, "sold")).isEqualByComparingTo(t.sold());
        assertThat(sum(outlets, "unpaid")).isEqualByComparingTo(t.unpaid());
        assertThat(outlets.stream().mapToLong(g -> g.money().cancelled()).sum()).isEqualTo(t.cancelled());

        List<SalesHistoryQuery.Group> people = query.byAttendant(day(ALL, null, "u1", null));
        assertThat(people).extracting(SalesHistoryQuery.Group::key).containsExactlyInAnyOrder("u1", "u2", "u3");
        assertThat(sum(people, "sold")).isEqualByComparingTo(t.sold());
        assertThat(sum(people, "paid")).isEqualByComparingTo(t.paid());
        assertThat(people.stream().mapToLong(g -> g.money().sales()).sum()).isEqualTo(t.sales());
        SalesHistoryQuery.Group segun = people.stream().filter(g -> g.key().equals("u1")).findFirst().orElseThrow();
        SalesHistoryQuery.Money3 hers = query.totals(day(ALL, null, "u1", null));
        assertThat(segun.money().sold()).isEqualByComparingTo(hers.sold());
        assertThat(segun.money().unpaid()).isEqualByComparingTo(hers.unpaid());
        assertThat(segun.name()).isEqualTo("Segun Ajayi");
    }

    @Test
    void unpaidSalesFromBeforeTheRangeStandOut() {
        SalesHistoryQuery.Earlier e = query.earlierUnpaid(day(ALL, null, null, null));
        assertThat(e.count()).isEqualTo(1);
        assertThat(e.amount()).isEqualByComparingTo("800");
        assertThat(e.oldest()).isEqualTo(Instant.parse("2026-11-07T12:00:00Z"));
        assertThat(query.earlierUnpaid(day(ALL, null, "u2", null)).count()).isZero();
    }

    @Test
    void pagesDoNotRepeatOrSkipASale() {
        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            seen.addAll(query.pageIds(day(ALL, null, null, null), page, 4));
        }
        assertThat(seen).hasSize(9);
        assertThat(new HashSet<>(seen)).hasSize(9);
    }

    @Test
    void anotherHotelsSalesNeverShow() {
        assertThat(query.pageIds(new SalesHistoryQuery.Filter(HOTEL, List.of("bar", "bar2"), Days.start(DAY), Days.end(DAY), ALL, null, null, null, null),
                0, 100)).doesNotContain("X1");
        assertThat(query.pageIds(day(DAY, ALL, null, null, null, List.of()), 0, 100)).isEmpty();
        assertThat(query.totals(day(DAY, ALL, null, null, null, List.of())).sales()).isZero();
    }

    private static BigDecimal sum(List<SalesHistoryQuery.Group> groups, String what) {
        return groups.stream().map(g -> switch (what) {
            case "sold" -> g.money().sold();
            case "paid" -> g.money().paid();
            default -> g.money().unpaid();
        }).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @SuppressWarnings("unused")
    private static Instant utc(String s) {
        return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
    }
}

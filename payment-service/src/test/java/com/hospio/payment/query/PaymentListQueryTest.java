package com.hospio.payment.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * The payments list SQL against a real Postgres with the service's Liquibase schema: pages without gaps or overlap, counts and money over
 * the whole filtered range (the same on every page), filters and the owner's business-wide scope. Skipped where Docker is not running.
 */
@Testcontainers(disabledWithoutDocker = true)
class PaymentListQueryTest {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    static PostgreSQLContainer<?> pg;
    static JdbcTemplate jdbc;
    static PaymentListQuery query;
    static final LocalDate DAY = LocalDate.of(2026, 11, 9);

    @BeforeAll
    static void start() throws Exception {
        pg = new PostgreSQLContainer<>("postgres:16-alpine");
        pg.start();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(pg.getJdbcUrl() + "&currentSchema=payment");
        ds.setUser(pg.getUsername());
        ds.setPassword(pg.getPassword());
        new JdbcTemplate(ds).execute("CREATE SCHEMA IF NOT EXISTS payment");
        SpringLiquibase lb = new SpringLiquibase();
        lb.setDataSource(ds);
        lb.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        lb.setDefaultSchema("payment");
        lb.setResourceLoader(new DefaultResourceLoader());
        lb.afterPropertiesSet();
        jdbc = new JdbcTemplate(ds);
        query = new PaymentListQuery(new NamedParameterJdbcTemplate(ds));
        // Hotel h1: 48 payments by u1 (front office) over 48 hours ending on DAY; every 4th declined, every 5th cash.
        for (int i = 0; i < 48; i++) {
            String status = i % 4 == 0 ? "DECLINED" : "APPROVED";
            String method = i % 5 == 0 ? "CASH" : "CARD";
            pay("p" + String.format("%02d", i), "b1", "h1", "u1", "FRONT_OFFICE", method, status, "1000.00", i % 7 == 0 ? "Ada Obi" : "Guest " + i,
                    DAY.atTime(23, 0).minusHours(i));
        }
        // Hotel h2 of the same business, by the bar; and another business.
        pay("x1", "b1", "h2", "u2", "BAR", "TRANSFER", "APPROVED", "2500.00", "Ada Obi", DAY.atTime(12, 0));
        pay("x2", "b1", "h2", "u2", "BAR", "CARD", "NO_RESPONSE", "700.00", "Bayo", DAY.atTime(13, 0));
        pay("z1", "b2", "h9", "u9", "BAR", "CASH", "APPROVED", "9999.00", "Ada Obi", DAY.atTime(12, 0));
    }

    @AfterAll
    static void stop() {
        if (pg != null) {
            pg.stop();
        }
    }

    static void pay(String id, String biz, String hotel, String by, String role, String method, String status, String amount, String guest,
                    LocalDateTime lagos) {
        LocalDateTime utc = lagos.atZone(LAGOS).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        jdbc.update("insert into payment_requests (id, business_id, hotel_id, requested_by, requested_by_name, role, method, amount, status, guest_name, "
                + "description, created_at, updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?)", id, biz, hotel, by, "Staff " + by, role, method,
                new BigDecimal(amount), status, guest, "Room charges", utc, utc);
    }

    static Instant start(LocalDate d) {
        return d.atStartOfDay(LAGOS).toInstant();
    }

    static PaymentListQuery.Filter hotel(String q, String status, String method) {
        return new PaymentListQuery.Filter("b1", "h1", null, q, status, method, null, null, null);
    }

    @Test
    void pagesCoverEveryRowOnceNewestFirst() {
        PaymentListQuery.Filter f = hotel(null, null, null);
        assertThat(query.count(f)).isEqualTo(48);
        List<String> all = new ArrayList<>();
        for (int page = 0; page < 2; page++) {
            all.addAll(query.ids(f, "created_at", false, page, 25));
        }
        assertThat(all).hasSize(48);
        assertThat(new HashSet<>(all)).hasSize(48);
        assertThat(all.get(0)).isEqualTo("p00");
        assertThat(all.get(25)).isEqualTo("p25");
        assertThat(query.ids(f, "created_at", false, 2, 25)).isEmpty();
        // Sorting by amount (all equal) still pages without overlap thanks to the tie-breakers.
        Set<String> byAmount = new HashSet<>(query.ids(f, "amount", true, 0, 25));
        byAmount.addAll(query.ids(f, "amount", true, 1, 25));
        assertThat(byAmount).hasSize(48);
    }

    @Test
    void countsAndMoneyIgnoreTheStatusAndCoverTheWholeRange() {
        PaymentListQuery.Filter declined = hotel(null, "DECLINED", null);
        assertThat(query.count(declined)).isEqualTo(12);
        Map<String, Long> counts = query.countsByStatus(declined);
        assertThat(counts).containsEntry("DECLINED", 12L).containsEntry("APPROVED", 36L).containsEntry("all", 48L);
        assertThat(query.approved(declined).get("approved")).isEqualByComparingTo("36000");
        assertThat(query.count(hotel(null, "DECLINED,APPROVED", null))).isEqualTo(48);
    }

    @Test
    void searchMethodAndDatesNarrowInTheDatabase() {
        assertThat(query.count(hotel("ada", null, null))).isEqualTo(7);
        assertThat(query.count(hotel("PAY-P0", null, null))).isEqualTo(10);
        assertThat(query.count(hotel("100%", null, null))).isZero();
        assertThat(query.count(hotel(null, null, "CASH"))).isEqualTo(10);
        PaymentListQuery.Filter day = new PaymentListQuery.Filter("b1", "h1", null, null, null, null, null, start(DAY), start(DAY.plusDays(1)));
        assertThat(query.count(day)).isEqualTo(24);
    }

    @Test
    void theOwnerSeesEveryHotelOfTheBusinessAndTheSumOfTheHotels() {
        PaymentListQuery.Filter business = new PaymentListQuery.Filter("b1", null, null, null, null, null, null, null, null);
        assertThat(query.count(business)).isEqualTo(50);
        PaymentListQuery.Window today = new PaymentListQuery.Window(start(DAY), start(DAY.plusDays(1)));
        PaymentListQuery.Window month = new PaymentListQuery.Window(start(DAY.withDayOfMonth(1)), start(DAY.plusDays(1)));
        Map<String, BigDecimal> all = query.period(business, today, month);
        Map<String, BigDecimal> h1 = query.period(hotel(null, null, null), today, month);
        Map<String, BigDecimal> h2 = query.period(new PaymentListQuery.Filter("b1", "h2", null, null, null, null, null, null, null), today, month);
        assertThat(all.get("approvedMonth")).isEqualByComparingTo(h1.get("approvedMonth").add(h2.get("approvedMonth")));
        assertThat(all.get("approvedToday")).isEqualByComparingTo(h1.get("approvedToday").add(h2.get("approvedToday")));
        assertThat(h2.get("todayTransfer")).isEqualByComparingTo("2500");
        assertThat(all.get("approvedMonthCount")).isEqualByComparingTo("37");
        // The other business never shows.
        assertThat(query.count(new PaymentListQuery.Filter("b1", null, null, "ada", null, null, null, null, null))).isEqualTo(8);
    }

    @Test
    void sliceCountsForTabsThatAreNotStatuses() {
        PaymentListQuery.Filter f = hotel(null, null, null);
        PaymentListQuery.Window today = new PaymentListQuery.Window(start(DAY), start(DAY.plusDays(1)));
        assertThat(query.countWhere(f, today, null)).isEqualTo(24);
        assertThat(query.countWhere(f, null, "u1")).isEqualTo(48);
        assertThat(query.countWhere(f, null, "u2")).isZero();
    }
}

package com.hospio.reservation.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ux_bookings_room_in_house against a real Postgres with the service's Liquibase schema: two check-ins into one room at the same
 * moment, exactly one commits; checking out frees the room; and the changeset waits (instead of failing the start) while old data
 * still has two guests in one room, then builds the index on the next start once that is cleaned.
 */
@Testcontainers(disabledWithoutDocker = true)
class RoomInHouseIndexTest {

    static PostgreSQLContainer<?> pg;
    static PGSimpleDataSource ds;
    static JdbcTemplate jdbc;
    static final LocalDate TODAY = LocalDate.of(2026, 11, 11);

    @BeforeAll
    static void start() throws Exception {
        pg = new PostgreSQLContainer<>("postgres:16-alpine");
        pg.start();
        ds = new PGSimpleDataSource();
        ds.setUrl(pg.getJdbcUrl() + "&currentSchema=reservation");
        ds.setUser(pg.getUsername());
        ds.setPassword(pg.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS reservation");
        migrate();
        jdbc.update("insert into reservation.guests (id, business_id, full_name, created_at, updated_at) values ('g1','b1','Guest',now(),now())");
    }

    @AfterAll
    static void stop() {
        if (pg != null) {
            pg.stop();
        }
    }

    static void migrate() throws Exception {
        SpringLiquibase lb = new SpringLiquibase();
        lb.setDataSource(ds);
        lb.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        lb.setDefaultSchema("reservation");
        lb.setResourceLoader(new DefaultResourceLoader());
        lb.afterPropertiesSet();
    }

    static boolean indexExists() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('reservation.ux_bookings_room_in_house') is not null", Boolean.class));
    }

    static void booking(String id, String room, String status) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into reservation.bookings (id, business_id, hotel_id, reference, guest_id, guest_name, room_type_id, room_id, room_number, "
                        + "check_in_date, check_out_date, status, nightly_rate, total_amount, created_at, updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, "b1", "h1", ("R" + id).toUpperCase(), "g1", "Guest " + id, "t1", room, room, TODAY, TODAY.plusDays(1), status,
                java.math.BigDecimal.TEN, java.math.BigDecimal.TEN, now, now);
    }

    static boolean checkIn(Connection c, String id) throws SQLException {
        try (var st = c.prepareStatement("update reservation.bookings set status = 'CHECKED_IN' where id = ?")) {
            st.setString(1, id);
            st.executeUpdate();
            c.commit();
            return true;
        } catch (SQLException e) {
            c.rollback();
            if ("23505".equals(e.getSQLState())) {
                return false;
            }
            throw e;
        }
    }

    @Test
    void twoCheckInsIntoOneRoomAtOnceExactlyOneWins() throws Exception {
        if (!indexExists()) {
            migrate();
        }
        assertThat(indexExists()).isTrue();
        booking("a1", "r-race", "BOOKED");
        booking("a2", "r-race", "BOOKED");
        for (int round = 0; round < 5; round++) {
            jdbc.update("update reservation.bookings set status = 'BOOKED' where room_id = 'r-race'");
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<Boolean>> results = new ArrayList<>();
            for (String id : List.of("a1", "a2")) {
                Callable<Boolean> task = () -> {
                    try (Connection c = ds.getConnection()) {
                        c.setAutoCommit(false);
                        go.await();
                        return checkIn(c, id);
                    }
                };
                results.add(pool.submit(task));
            }
            go.countDown();
            int won = 0;
            for (Future<Boolean> f : results) {
                won += f.get() ? 1 : 0;
            }
            pool.shutdown();
            assertThat(won).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from reservation.bookings where room_id = 'r-race' and status = 'CHECKED_IN'", Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void checkingOutFreesTheRoomAndOtherStatusesNeverClash() throws Exception {
        if (!indexExists()) {
            migrate();
        }
        booking("c1", "r-turn", "CHECKED_IN");
        booking("c2", "r-turn", "BOOKED");
        booking("c3", "r-turn", "CHECKED_OUT");
        booking("c4", "r-turn", "CANCELLED");
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            assertThat(checkIn(c, "c2")).isFalse();
        }
        jdbc.update("update reservation.bookings set status = 'CHECKED_OUT' where id = 'c1'");
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            assertThat(checkIn(c, "c2")).isTrue();
        }
    }

    @Test
    void theChangesetWaitsForBadDataInsteadOfFailingTheStart() throws Exception {
        jdbc.execute("drop index if exists reservation.ux_bookings_room_in_house");
        booking("d1", "r-dup", "CHECKED_IN");
        booking("d2", "r-dup", "CHECKED_IN");

        migrate();
        assertThat(indexExists()).isFalse();

        jdbc.update("update reservation.bookings set status = 'CHECKED_OUT' where id = 'd2'");
        migrate();
        assertThat(indexExists()).isTrue();
    }
}

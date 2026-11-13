package com.hospio.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Function;
import liquibase.integration.spring.SpringLiquibase;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.DefaultPersistenceUnitManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The booking repository's new queries against a real Postgres with the service's schema (Hibernate validates the mapping, version
 * column included): a payment is recorded once, amount paid moves in one statement and never below zero, the row lock reads the row,
 * and room nights are summed in SQL over the nights inside the range only.
 */
@Testcontainers(disabledWithoutDocker = true)
class BookingPaymentsSqlTest {

    static PostgreSQLContainer<?> pg;
    static JdbcTemplate jdbc;
    static EntityManagerFactory emf;

    @BeforeAll
    static void start() throws Exception {
        pg = new PostgreSQLContainer<>("postgres:16-alpine");
        pg.start();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(pg.getJdbcUrl() + "&currentSchema=reservation");
        ds.setUser(pg.getUsername());
        ds.setPassword(pg.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS reservation");
        SpringLiquibase lb = new SpringLiquibase();
        lb.setDataSource(ds);
        lb.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        lb.setDefaultSchema("reservation");
        lb.setResourceLoader(new DefaultResourceLoader());
        lb.afterPropertiesSet();

        DefaultPersistenceUnitManager pum = new DefaultPersistenceUnitManager();
        pum.setPackagesToScan("com.hospio.reservation.entity");
        pum.setDefaultDataSource(ds);
        pum.afterPropertiesSet();
        LocalContainerEntityManagerFactoryBean f = new LocalContainerEntityManagerFactoryBean();
        f.setPersistenceUnitManager(pum);
        f.setPersistenceProviderClass(HibernatePersistenceProvider.class);
        f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.default_schema", "reservation"));
        f.afterPropertiesSet();
        emf = f.getObject();

        jdbc.update("insert into reservation.guests (id, business_id, full_name, created_at, updated_at) values ('g1','b1','Guest',now(),now())");
        booking("p1", "CHECKED_IN", LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 3), "10000");
        booking("p2", "CHECKED_OUT", LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12), "20000");
        booking("p3", "CANCELLED", LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 7), "50000");
    }

    @AfterAll
    static void stop() {
        if (emf != null) {
            emf.close();
        }
        if (pg != null) {
            pg.stop();
        }
    }

    static void booking(String id, String status, LocalDate in, LocalDate out, String rate) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into reservation.bookings (id, business_id, hotel_id, reference, guest_id, guest_name, room_type_id, "
                        + "check_in_date, check_out_date, status, nightly_rate, total_amount, created_at, updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, "b1", "h1", ("R" + id).toUpperCase(), "g1", "Guest " + id, "t1", in, out, status, new BigDecimal(rate), new BigDecimal(rate), now, now);
    }

    static <T> T inTx(Function<BookingRepository, T> work) {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            T out = work.apply(new JpaRepositoryFactory(em).getRepository(BookingRepository.class));
            em.getTransaction().commit();
            return out;
        } finally {
            em.close();
        }
    }

    static BigDecimal paid(String id) {
        return jdbc.queryForObject("select amount_paid from reservation.bookings where id = ?", BigDecimal.class, id);
    }

    @Test
    void aPaymentIsRecordedOnceAndAmountPaidNeverGoesBelowZero() {
        int first = inTx(r -> r.recordPayment("payment:x1", "p1", new BigDecimal("3000")));
        int again = inTx(r -> r.recordPayment("payment:x1", "p1", new BigDecimal("3000")));
        int noBooking = inTx(r -> r.recordPayment("payment:x2", "nope", new BigDecimal("3000")));
        assertThat(first).isEqualTo(1);
        assertThat(again).isZero();
        assertThat(noBooking).isZero();

        int updated = inTx(r -> r.addPaid("p1", new BigDecimal("3000"), Instant.now()));
        assertThat(updated).isEqualTo(1);
        assertThat(paid("p1")).isEqualByComparingTo("3000");
        inTx(r -> r.addPaid("p1", new BigDecimal("-5000"), Instant.now()));
        assertThat(paid("p1")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select version from reservation.bookings where id = 'p1'", Long.class)).isEqualTo(2L);
    }

    @Test
    void theRowLockReadsTheBookingByIdAndReference() {
        String status = inTx(r -> r.lockById("p2").map(b -> b.getStatus().name()).orElseThrow());
        String id = inTx(r -> r.idByReference("RP2").orElseThrow());
        assertThat(status).isEqualTo("CHECKED_OUT");
        assertThat(id).isEqualTo("p2");
    }

    @Test
    void roomNightsAreSummedInsideTheRangeOnly() {
        // November 1..30: p1 has 2 nights inside (1st, 2nd) at 10000, p2 has 2 at 20000; the cancelled stay is left out.
        Object[] row = inTx(r -> r.nightsInBusiness("b1", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1)).get(0));
        assertThat(((Number) row[0]).longValue()).isEqualTo(4);
        assertThat(new BigDecimal(row[1].toString())).isEqualByComparingTo("60000");
    }
}

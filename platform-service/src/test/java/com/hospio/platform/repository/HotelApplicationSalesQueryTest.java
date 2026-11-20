package com.hospio.platform.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.common.paging.Paging;
import com.hospio.platform.entity.HotelApplication;
import com.hospio.platform.entity.MonthlySales;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Hotel applications' monthly sales band in a real Postgres: the filter (a band, or NOT_ASKED for requests sent before the question)
 * and the sort (highest band first, Prefer not to say then Not asked last, newest first within a band) through the same
 * specification and sort the service uses. The schema comes from the entities, not Liquibase, so it runs on a fresh database.
 * Needs Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.liquibase.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class HotelApplicationSalesQueryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("platform-schema.sql");

    @Autowired
    HotelApplicationRepository applications;
    @Autowired
    TestEntityManager em;

    private final Instant start = Instant.parse("2026-11-20T09:00:00Z");
    private int n;

    @BeforeEach
    void seed() {
        applications.deleteAll();
        n = 0;
        // Saved oldest first; the names say band and age.
        save("old-none", null);
        save("under-a", "UNDER_1M");
        save("prefer", "PREFER_NOT");
        save("top-a", "ABOVE_20M");
        save("mid", "FROM_5M_TO_20M");
        save("low", "FROM_1M_TO_5M");
        save("under-b", "UNDER_1M");
        save("top-b", "ABOVE_20M");
        save("old-none-b", null);
    }

    private void save(String name, String band) {
        Instant at = start.plusSeconds(60L * n++);
        HotelApplication a = HotelApplication.builder().ownerName("QA " + name).email(name + "@hospio-qa.test").phone("+2348035550192")
                .businessName("QA " + name).businessType("HOTEL").city("Ikeja").state("Lagos").heardFrom("SEARCH").monthlySales(band)
                .status(HotelApplication.NEW).build();
        a.setCreatedAt(at);
        a.setUpdatedAt(at);
        applications.saveAndFlush(a);
    }

    private List<String> names(String band, String sort) {
        return applications.findAll(com.hospio.platform.service.HotelApplicationServiceProbe.spec(band),
                Paging.of(0, 25, com.hospio.platform.service.HotelApplicationServiceProbe.order(sort))).map(HotelApplication::getOwnerName)
                .map(s -> s.substring(3)).getContent();
    }

    @Test
    void sortingBySalesPutsTheHighestBandFirstThenPreferNotThenNotAsked() {
        assertThat(names(null, "SALES")).containsExactly("top-b", "top-a", "mid", "low", "under-b", "under-a", "prefer", "old-none-b", "old-none");
        assertThat(names(null, null)).containsExactly("old-none-b", "top-b", "under-b", "low", "mid", "top-a", "prefer", "under-a", "old-none");
    }

    @Test
    void theFilterKeepsOneBandOrTheRequestsSentBeforeTheQuestion() {
        assertThat(names("UNDER_1M", null)).containsExactly("under-b", "under-a");
        assertThat(names("ABOVE_20M", "SALES")).containsExactly("top-b", "top-a");
        assertThat(names(MonthlySales.NOT_ASKED, null)).containsExactly("old-none-b", "old-none");
        assertThat(names("PREFER_NOT", null)).containsExactly("prefer");
        assertThat(applications.count(com.hospio.platform.service.HotelApplicationServiceProbe.spec("FROM_5M_TO_20M"))).isEqualTo(1);
    }

    @Test
    void theRankIsReadBackWithTheRow() {
        em.clear();
        assertThat(applications.findAll(Sort.by("createdAt"))).extracting(HotelApplication::getMonthlySalesRank)
                .containsExactly(6, 4, 5, 1, 2, 3, 4, 1, 6);
    }
}

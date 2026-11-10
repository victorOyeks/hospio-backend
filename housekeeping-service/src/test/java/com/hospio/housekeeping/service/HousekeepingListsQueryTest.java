package com.hospio.housekeeping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.housekeeping.dto.LaundryDtos;
import com.hospio.housekeeping.entity.LaundryOrder;
import com.hospio.housekeeping.entity.LostFoundItem;
import com.hospio.housekeeping.repository.LaundryOrderRepository;
import com.hospio.housekeeping.repository.LostFoundItemRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Paging of lost & found and laundry orders against a real Postgres built by the service's Liquibase changelog: page boundaries without
 * gaps or overlap, the search and dates applied in the database, counts over the whole filtered range, size above 100 refused.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.liquibase.enabled=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({HousekeepingLists.class, GroupCounts.class})
class HousekeepingListsQueryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            // The service's pool creates its schema on connect; here the database starts with it.
            .withUrlParam("currentSchema", "housekeeping")
            .withCopyToContainer(org.testcontainers.images.builder.Transferable.of("CREATE SCHEMA IF NOT EXISTS housekeeping;"),
                    "/docker-entrypoint-initdb.d/00-schema.sql");

    static final String HOTEL = "hotel-1";

    @Autowired
    HousekeepingLists lists;
    @Autowired
    LostFoundItemRepository lostFound;
    @Autowired
    LaundryOrderRepository orders;
    @Autowired
    JdbcTemplate jdbc;
    @MockBean
    LaundryService laundry;

    @BeforeEach
    void tenant() {
        TenantContext.set(Tenant.systemFor("biz-1", HOTEL, "u1", "Tester"));
        when(laundry.orderView(any())).thenAnswer(a -> Mockito.mock(LaundryDtos.OrderView.class));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void lostAndFoundPagesWithoutGapsAndCountsTheWholeRange() {
        Instant now = Instant.now();
        for (int i = 0; i < 48; i++) {
            lostFound.save(LostFoundItem.builder().businessId("biz-1").hotelId(HOTEL).description((i % 2 == 0 ? "Phone " : "Watch ") + i)
                    .foundWhere("Room " + i).foundAt(now.minus(i, ChronoUnit.HOURS))
                    .status(i < 10 ? LostFoundItem.Status.RETURNED : LostFoundItem.Status.FOUND).build());
        }
        lostFound.save(LostFoundItem.builder().businessId("biz-1").hotelId("other-hotel").description("Phone elsewhere").foundAt(now)
                .status(LostFoundItem.Status.FOUND).build());
        lostFound.flush();

        PageWithCounts<LostFoundItem> p0 = lists.lostAndFound(new HousekeepingLists.Query(null, null, null, null, null, 0, 25));
        PageWithCounts<LostFoundItem> p1 = lists.lostAndFound(new HousekeepingLists.Query(null, null, null, null, null, 1, 25));
        assertThat(p0.totalElements()).isEqualTo(48);
        assertThat(p0.totalPages()).isEqualTo(2);
        assertThat(p0.content()).hasSize(25);
        assertThat(p1.content()).hasSize(23);
        Set<String> ids = new HashSet<>();
        p0.content().forEach(x -> ids.add(x.getId()));
        p1.content().forEach(x -> ids.add(x.getId()));
        assertThat(ids).hasSize(48);
        // Newest first: the first row of page 2 is the 26th newest.
        assertThat(p1.content().get(0).getDescription()).endsWith(" 25");
        assertThat(p0.counts()).containsEntry("all", 48L).containsEntry("FOUND", 38L).containsEntry("RETURNED", 10L);
        assertThat(p1.counts()).isEqualTo(p0.counts());

        PageWithCounts<LostFoundItem> found = lists.lostAndFound(new HousekeepingLists.Query("watch", "FOUND", null, null, null, 0, 25));
        assertThat(found.content()).allMatch(x -> x.getDescription().startsWith("Watch") && x.getStatus() == LostFoundItem.Status.FOUND);
        assertThat(found.totalElements()).isEqualTo(19);
        // Counts keep every filter but the status.
        assertThat(found.counts()).containsEntry("all", 24L);

        assertThatThrownBy(() -> lists.lostAndFound(new HousekeepingLists.Query(null, null, null, null, null, 0, 101)))
                .isInstanceOf(BadRequestException.class).hasMessage("size is 1 to 100");
        assertThatThrownBy(() -> lists.lostAndFound(new HousekeepingLists.Query(null, "LOST", null, null, null, 0, 25)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void laundryOrdersFilterByStatusAliasAndDatesAndAddUpTheValue() {
        for (int i = 0; i < 30; i++) {
            LaundryOrder.Status s = i < 5 ? LaundryOrder.Status.RECEIVED : i < 8 ? LaundryOrder.Status.READY : LaundryOrder.Status.DELIVERED;
            LaundryOrder o = orders.saveAndFlush(LaundryOrder.builder().businessId("biz-1").hotelId(HOTEL).number("LDR-" + i).guestName("Guest " + i)
                    .items("[]").total(new BigDecimal("1000.00")).status(s).build());
            jdbc.update("update laundry_orders set created_at = ? where id = ?", java.sql.Timestamp.from(Instant.parse("2026-11-01T12:00:00Z")
                    .plus(i, ChronoUnit.DAYS)), o.getId());
        }
        orders.flush();
        PageWithCounts<LaundryDtos.OrderView> open = lists.laundryOrders(null, null, new HousekeepingLists.Query(null, "open", null, null, null, 0, 25));
        assertThat(open.totalElements()).isEqualTo(8);
        assertThat(open.counts()).containsEntry("OPEN", 8L).containsEntry("DELIVERED", 22L).containsEntry("all", 30L);
        assertThat(open.totals().get("value")).isEqualByComparingTo("8000");

        PageWithCounts<LaundryDtos.OrderView> week = lists.laundryOrders(null, null,
                new HousekeepingLists.Query(null, null, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7), null, 0, 25));
        assertThat(week.totalElements()).isEqualTo(7);
        PageWithCounts<LaundryDtos.OrderView> guest = lists.laundryOrders(null, null, new HousekeepingLists.Query("guest 2", null, null, null, null, 0, 25));
        assertThat(guest.totalElements()).isEqualTo(11); // Guest 2, 20-29
        assertThat(List.of(guest.totalPages())).containsExactly(1);
    }
}

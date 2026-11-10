package com.hospio.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.maintenance.dto.MaintenanceViews;
import com.hospio.maintenance.entity.Repair;
import com.hospio.maintenance.repository.RepairRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Work orders paged in a real Postgres (the service's Liquibase schema): no Top300 cap, every tab counted over the same search, done
 * ordered by when it finished, size above 100 refused.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(RepairLists.class)
class RepairListsQueryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("currentSchema", "maintenance")
            .withCopyToContainer(Transferable.of("CREATE SCHEMA IF NOT EXISTS maintenance;"), "/docker-entrypoint-initdb.d/00-schema.sql");

    static final String HOTEL = "hotel-1";

    @Autowired
    RepairLists lists;
    @Autowired
    RepairRepository repairs;
    @Autowired
    JdbcTemplate jdbc;
    @MockBean
    MaintenanceService service;

    @BeforeEach
    void signIn() {
        TenantContext.set(Tenant.systemFor("biz-1", HOTEL, "tech-1", "Chinedu"));
        HospioUserDetails u = new HospioUserDetails("tech-1", "c@x.ng", "Chinedu", "biz-1", List.of(HOTEL),
                Map.of(Role.MAINTENANCE, AccessLevel.FULL), "HOTEL");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    @AfterEach
    void out() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void pagesPastThreeHundredAndCountsEveryTab() {
        Instant now = Instant.now();
        // 320 done (more than the old cap), 6 open of which 2 mine, 1 on hold, 2 overdue.
        for (int i = 0; i < 320; i++) {
            Repair r = repairs.saveAndFlush(Repair.builder().businessId("biz-1").hotelId(HOTEL).number("WO-" + i).title("Leak " + i)
                    .area("Lobby").status(i % 10 == 0 ? Repair.Status.CANCELLED : Repair.Status.DONE).build());
            jdbc.update("update repairs set done_at = ? where id = ?", java.sql.Timestamp.from(now.minus(i, ChronoUnit.MINUTES)), r.getId());
        }
        for (int i = 0; i < 6; i++) {
            repairs.saveAndFlush(Repair.builder().businessId("biz-1").hotelId(HOTEL).number("WO-O" + i).title(i == 0 ? "Broken AC" : "Bulb " + i)
                    .area("Room").assignedTo(i < 2 ? "tech-1" : null).status(i == 5 ? Repair.Status.ON_HOLD : Repair.Status.OPEN)
                    .dueAt(i < 2 ? now.minus(1, ChronoUnit.HOURS) : now.plus(1, ChronoUnit.DAYS)).build());
        }
        repairs.saveAndFlush(Repair.builder().businessId("biz-1").hotelId("hotel-2").number("WO-X").title("Elsewhere").area("Bar").build());

        PageWithCounts<MaintenanceViews.RepairView> done = lists.page(new RepairLists.Query("done", null, null, null, null, null, 12, 25));
        assertThat(done.totalElements()).isEqualTo(320);
        assertThat(done.totalPages()).isEqualTo(13);
        assertThat(done.content()).hasSize(20);
        assertThat(done.counts()).containsEntry("done", 320L).containsEntry("open", 6L).containsEntry("mine", 2L).containsEntry("overdue", 2L)
                .containsEntry("held", 1L).containsEntry("unassigned", 4L).containsEntry("all", 326L);

        PageWithCounts<MaintenanceViews.RepairView> ac = lists.page(new RepairLists.Query("all", null, "broken ac", null, null, null, 0, 25));
        assertThat(ac.totalElements()).isEqualTo(1);
        assertThat(ac.counts()).containsEntry("open", 1L).containsEntry("done", 0L);

        assertThatThrownBy(() -> lists.page(new RepairLists.Query("done", null, null, null, null, null, 0, 101)))
                .isInstanceOf(BadRequestException.class).hasMessage("size is 1 to 100");
        assertThatThrownBy(() -> lists.page(new RepairLists.Query("later", null, null, null, null, null, 0, 25)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> lists.page(new RepairLists.Query("done", null, null, null, null, "password", 0, 25)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void doneIsNewestFinishedFirstWithoutOverlap() {
        Instant now = Instant.now();
        for (int i = 0; i < 30; i++) {
            Repair r = repairs.saveAndFlush(Repair.builder().businessId("biz-1").hotelId(HOTEL).number("D-" + i).title("Done " + i).area("Spa")
                    .status(Repair.Status.DONE).build());
            jdbc.update("update repairs set done_at = ? where id = ?", java.sql.Timestamp.from(now.minus(i, ChronoUnit.HOURS)), r.getId());
        }
        List<String> seen = new java.util.ArrayList<>();
        org.mockito.Mockito.when(service.view(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(a -> {
                    seen.add(((Repair) a.getArgument(0)).getNumber());
                    return null;
                });
        lists.page(new RepairLists.Query("done", null, null, null, null, null, 0, 25));
        lists.page(new RepairLists.Query("done", null, null, null, null, null, 1, 25));
        assertThat(seen).hasSize(30).doesNotHaveDuplicates();
        assertThat(seen.get(0)).isEqualTo("D-0");
        assertThat(seen.get(25)).isEqualTo("D-25");
    }
}

package com.hospio.finance.race;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.finance.client.PeerServices;
import com.hospio.finance.dto.FinanceRequests;
import com.hospio.finance.entity.Expense;
import com.hospio.finance.entity.RevenueEntry;
import com.hospio.finance.entity.Till;
import com.hospio.finance.repository.ExpenseRepository;
import com.hospio.finance.repository.RevenueEntryRepository;
import com.hospio.finance.repository.TillRepository;
import com.hospio.finance.service.MoneyService;
import com.hospio.finance.service.ReportingService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tills under concurrency and the owner's figures summed in SQL, against a real Postgres: one open till per role, cash moved by many
 * people at once all counted, the owner's summary per hotel. Needs Docker (skipped without it).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.admin.fail-fast=false",
        "services.internal.api-key=test-key",
        "hospio.worker.enabled=false",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-test"
})
class TillRaceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("finance-schema.sql");

    @MockBean
    PeerServices peers;
    @MockBean
    com.hospio.common.kafka.EventPublisher events;

    @Autowired
    MoneyService money;
    @Autowired
    ReportingService reporting;
    @Autowired
    TillRepository tills;
    @Autowired
    RevenueEntryRepository revenue;
    @Autowired
    ExpenseRepository expenses;
    @Autowired
    JdbcTemplate jdbc;

    private HospioUserDetails manager;

    @BeforeEach
    void signIn() {
        jdbc.update("delete from finance.till_entries");
        jdbc.update("delete from finance.tills");
        manager = new HospioUserDetails("m1", "m@x.ng", "Mgr", "b1", List.of("h1", "h2"), Map.of(Role.MANAGER, AccessLevel.FULL, Role.OWNER, AccessLevel.FULL),
                JwtUtil.SCOPE_HOTEL);
        as(manager, "h1");
    }

    private static void as(HospioUserDetails u, String hotel) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        TenantContext.set(Tenant.fromUser(u, hotel));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void aRoleHasOneOpenTillEvenWhenOpenedTwiceAtOnce() throws Exception {
        List<Future<Object>> results = race(4, () -> money.openTill(new FinanceRequests.OpenTillRequest("BAR", new BigDecimal("1000"))));

        int opened = 0;
        for (Future<Object> f : results) {
            try {
                f.get();
                opened++;
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(BadRequestException.class).hasMessageContaining("already open");
            }
        }
        assertThat(opened).isEqualTo(1);
        assertThat(tills.findByHotelIdAndStatusOrderByOpenedAt("h1", Till.Status.OPEN)).hasSize(1);
    }

    @Test
    void cashMovedByManyPeopleAtOnceIsAllCounted() throws Exception {
        Till t = money.openTill(new FinanceRequests.OpenTillRequest("FRONT_OFFICE", new BigDecimal("1000")));
        int n = 8;
        for (Future<Object> f : race(n, () -> money.addMovement(t.getId(), new FinanceRequests.MovementRequest("PAY_IN", new BigDecimal("100"), "Change")))) {
            f.get();
        }
        Till after = tills.findById(t.getId()).orElseThrow();
        assertThat(after.getExpectedCash()).isEqualByComparingTo(new BigDecimal(1000 + 100 * n));
        assertThat(after.getCashIn()).isEqualByComparingTo(new BigDecimal(100 * n));
    }

    @Test
    void nothingLandsOnAClosedTillAndAHandoverOpensTheNextOne() {
        Till t = money.openTill(new FinanceRequests.OpenTillRequest("KITCHEN", BigDecimal.ZERO));
        MoneyService.Handover h = money.handover(t.getId(), new FinanceRequests.HandoverRequest(BigDecimal.ZERO, null, "u2", "Next"));
        assertThat(h.opened().getStatus()).isEqualTo(Till.Status.OPEN);
        assertThatThrownBy(() -> money.addMovement(t.getId(), new FinanceRequests.MovementRequest("PAY_IN", BigDecimal.TEN, "Late")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("closed");
        assertThatThrownBy(() -> money.closeTill(t.getId(), new FinanceRequests.CloseTillRequest(BigDecimal.ZERO, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already closed");
    }

    @Test
    void theOwnersSummaryIsSummedPerHotelInTheDatabase() {
        LocalDate day = LocalDate.of(2026, 11, 10);
        TenantContext.runWith(Tenant.system("seed"), () -> {
            for (String[] r : new String[][]{{"h1", "1000"}, {"h1", "500"}, {"h2", "700"}, {"h9", "99999"}}) {
                revenue.saveAndFlush(RevenueEntry.builder().businessId(r[0].equals("h9") ? "b9" : "b1").hotelId(r[0]).paymentId(UUID.randomUUID().toString())
                        .businessDate(day).amount(new BigDecimal(r[1])).method("CASH").build());
            }
            expenses.saveAndFlush(Expense.builder().businessId("b1").hotelId("h1").category("Office").amount(new BigDecimal("300")).description("Paper")
                    .paidFrom("CASH").expenseDate(day).requestedBy("m1").status(Expense.Status.RECORDED).build());
            expenses.saveAndFlush(Expense.builder().businessId("b1").hotelId("h1").category("Office").amount(new BigDecimal("9000")).description("No")
                    .paidFrom("CASH").expenseDate(day).requestedBy("m1").status(Expense.Status.DECLINED).build());
            return null;
        });
        when(peers.hotels("b1")).thenReturn(List.of(new PeerServices.Hotel("h1", "One"), new PeerServices.Hotel("h2", "Two")));
        when(peers.roomCount(any())).thenReturn(10);
        when(peers.daySoft(any(), any())).thenReturn(Optional.empty());

        ReportingService.OwnerSummary s = reporting.ownerSummary(day, day);

        assertThat(s.hotels()).extracting(ReportingService.HotelLine::revenue).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1500"), new BigDecimal("700"));
        assertThat(s.hotels().get(0).expenses()).isEqualByComparingTo("300");
        assertThat(s.hotels().get(0).profit()).isEqualByComparingTo("1200");
        assertThat(s.totals().revenue()).isEqualByComparingTo("2200");
        assertThat(reporting.series(day, day).get(0).revenue()).isEqualByComparingTo("1500");
        assertThat(reporting.series(day, day).get(0).expenses()).isEqualByComparingTo("300");
    }

    /** Runs the task on n threads released together, each signed in as the manager at h1. */
    private List<Future<Object>> race(int n, Callable<Object> task) throws InterruptedException {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<Object>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(pool.submit(() -> {
                as(manager, "h1");
                try {
                    go.await();
                    return task.call();
                } finally {
                    SecurityContextHolder.clearContext();
                    TenantContext.clear();
                }
            }));
        }
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);
        return out;
    }
}

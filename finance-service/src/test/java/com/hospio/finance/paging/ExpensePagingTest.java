package com.hospio.finance.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.response.PagedResponse;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.finance.entity.Expense;
import com.hospio.finance.entity.NightAudit;
import com.hospio.finance.repository.ExpenseRepository;
import com.hospio.finance.repository.NightAuditRepository;
import com.hospio.finance.service.ExpenseQueryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Expenses and night audits paged in Postgres: tabs, counts and totals over the range, the recent order, the window. Needs Docker. */
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
class ExpensePagingTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("finance-schema.sql");

    @Autowired
    ExpenseRepository expenses;
    @Autowired
    NightAuditRepository audits;
    @Autowired
    ExpenseQueryService query;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void signIn() {
        jdbc.update("delete from finance.expenses");
        jdbc.update("delete from finance.night_audits");
        HospioUserDetails u = new HospioUserDetails("femi", "f@x.ng", "Femi", "b1", List.of("h1"), Map.of(Role.FINANCE, AccessLevel.FULL),
                JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        TenantContext.set(Tenant.fromUser(u, "h1"));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private Expense add(String hotel, Expense.Status status, String desc, int amount, int daysAgo) {
        return expenses.saveAndFlush(Expense.builder().businessId("b1").hotelId(hotel).category(desc.contains("Fuel") ? "Fuel & power" : "Office")
                .amount(BigDecimal.valueOf(amount)).description(desc).paidFrom("CASH").expenseDate(LocalDate.now().minusDays(daysAgo))
                .requestedBy("femi").requestedByName("Femi Ade").status(status).build());
    }

    @Test
    void expensesPageWithTabsCountsAndTotals() {
        List<String> order = new ArrayList<>();
        for (int i = 1; i <= 26; i++) {
            order.add(add("h1", Expense.Status.RECORDED, "QA PG expense " + i, 100, i % 5).getId());
        }
        add("h1", Expense.Status.PENDING_APPROVAL, "QA PG Fuel waiting", 50_000, 2);
        add("h1", Expense.Status.PENDING_APPROVAL, "QA PG Fuel waiting old", 7_000, 200); // open: listed although old
        add("h1", Expense.Status.QUESTION, "QA PG question", 900, 3);
        add("h1", Expense.Status.PAID, "QA PG paid", 1_000, 4);
        add("h1", Expense.Status.DECLINED, "QA PG declined", 5_000, 1);
        add("h1", Expense.Status.RECORDED, "QA PG ancient", 3, 150); // settled and old: outside the window
        add("h2", Expense.Status.RECORDED, "Other hotel", 1, 1);

        ExpenseQueryService.Filter all = new ExpenseQueryService.Filter(null, null, null, null, null, null);
        PageWithCounts<Expense> p0 = query.page(all, 0, 25, null);
        PageWithCounts<Expense> p1 = query.page(all, 1, 25, null);
        assertThat(p0.totalElements()).isEqualTo(31);
        assertThat(p1.content()).hasSize(6);
        Set<String> ids = new HashSet<>();
        p0.content().forEach(e -> ids.add(e.getId()));
        p1.content().forEach(e -> ids.add(e.getId()));
        assertThat(ids).hasSize(31);
        assertThat(p0.counts()).isEqualTo(p1.counts()).containsEntry("all", 31L).containsEntry("approval", 2L).containsEntry("questions", 1L)
                .containsEntry("paid", 1L).containsEntry("requests", 3L);
        assertThat(p0.totals().get("all")).isEqualByComparingTo("66500");
        assertThat(p0.totals().get("recorded")).isEqualByComparingTo("61500");
        assertThat(p0.totals().get("requests")).isEqualByComparingTo("57900");
        // newest first by date, then by when recorded
        for (int i = 1; i < p0.content().size(); i++) {
            Expense a = p0.content().get(i - 1);
            Expense b = p0.content().get(i);
            assertThat(a.getExpenseDate()).isAfterOrEqualTo(b.getExpenseDate());
            if (a.getExpenseDate().equals(b.getExpenseDate())) {
                assertThat(a.getCreatedAt()).isAfterOrEqualTo(b.getCreatedAt());
            }
        }
        assertThat(query.page(new ExpenseQueryService.Filter("approval", null, null, null, null, null), 0, 25, null).totalElements()).isEqualTo(2);
        assertThat(query.page(new ExpenseQueryService.Filter(null, null, "Fuel & power", null, null, null), 0, 25, null).totalElements()).isEqualTo(2);
        PageWithCounts<Expense> searched = query.page(new ExpenseQueryService.Filter(null, null, null, "expense 2", null, null), 0, 25, null);
        assertThat(searched.totalElements()).isEqualTo(8); // 2, 20-26
        assertThat(searched.totals().get("matching")).isEqualByComparingTo("800");
        assertThat(query.page(new ExpenseQueryService.Filter(null, null, null, null, LocalDate.now().minusDays(400), null), 0, 25, null)
                .totalElements()).isEqualTo(32);
        assertThat(query.page(all, 0, 5, "amount,desc").content().get(0).getDescription()).isEqualTo("QA PG Fuel waiting");
        assertThatThrownBy(() -> query.page(all, 0, 101, null)).isInstanceOf(BadRequestException.class).hasMessage("size is 1 to 100");
        assertThatThrownBy(() -> query.page(new ExpenseQueryService.Filter("bogus", null, null, null, null, null), 0, 25, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void nightAuditsPageNewestDayFirst() {
        for (int i = 0; i < 30; i++) {
            audits.saveAndFlush(NightAudit.builder().businessId("b1").hotelId("h1").businessDate(LocalDate.of(2026, 10, 1).plusDays(i))
                    .status(i == 29 ? NightAudit.Status.OPEN : NightAudit.Status.CLOSED).build());
        }
        PagedResponse<NightAudit> p0 = query.audits(null, null, null, 0, 25);
        PagedResponse<NightAudit> p1 = query.audits(null, null, null, 1, 25);
        assertThat(p0.totalElements()).isEqualTo(30);
        assertThat(p0.content().get(0).getBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(p1.content()).hasSize(5);
        assertThat(p1.content().get(4).getBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(query.audits("CLOSED", null, null, 0, 25).totalElements()).isEqualTo(29);
        assertThat(query.audits(null, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12), 0, 25).totalElements()).isEqualTo(3);
    }
}

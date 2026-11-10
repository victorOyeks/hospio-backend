package com.hospio.platform.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.ApprovalStatus;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.entity.Approval;
import com.hospio.platform.repository.ApprovalRepository;
import com.hospio.platform.service.ApprovalQueryService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/** The approvals list paged in Postgres: tabs, counts and money over the range, the window that never hides an open one. Needs Docker. */
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
class ApprovalPagingTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("platform-schema.sql");

    @Autowired
    ApprovalRepository repo;
    @Autowired
    ApprovalQueryService query;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void signIn() {
        jdbc.update("delete from platform.approvals");
        HospioUserDetails u = new HospioUserDetails("mgr", "t@x.ng", "Tunde", "b1", List.of("h1"), Map.of(Role.MANAGER, AccessLevel.FULL),
                JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        TenantContext.set(Tenant.fromUser(u, "h1"));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private void add(String business, String level, ApprovalStatus status, String requester, String desc, int amount, int daysAgo) {
        Approval a = Approval.builder().businessId(business).hotelId("h1").sourceModule("FINANCE").kind("EXPENSE").amount(BigDecimal.valueOf(amount))
                .requesterId(requester).requesterName("Femi").approverLevel(level).description(desc).status(status).hotelName("Simpas")
                .requesterRole("FINANCE").build();
        a.setId(java.util.UUID.randomUUID().toString());
        repo.saveAndFlush(a);
        jdbc.update("update platform.approvals set created_at = ? where id = ?", Timestamp.from(Instant.now().minus(daysAgo, ChronoUnit.DAYS)
                .minusSeconds(desc.length() * 7L + amount % 97)), a.getId());
    }

    @Test
    void tabsCountsTotalsAndPages() {
        for (int i = 1; i <= 28; i++) {
            add("b1", "MANAGER", ApprovalStatus.PENDING, "femi", "QA PG waiting " + i, 1000, 1);
        }
        add("b1", "MANAGER", ApprovalStatus.PENDING, "femi", "QA PG ancient waiting", 500, 200);   // open: never hidden by the window
        add("b1", "OWNER", ApprovalStatus.PENDING, "mgr", "QA PG with the owner", 2_000_000, 2);
        add("b1", "MANAGER", ApprovalStatus.QUESTION, "mgr", "QA PG question to me", 300, 3);     // waiting (mine) and decided
        add("b1", "MANAGER", ApprovalStatus.APPROVED, "femi", "QA PG approved", 100, 5);
        add("b1", "MANAGER", ApprovalStatus.DECLINED, "femi", "QA PG declined old", 100, 150);     // closed and old: outside the window
        add("b2", "MANAGER", ApprovalStatus.PENDING, "x", "Other business", 1, 1);

        ApprovalQueryService.Filter waiting = new ApprovalQueryService.Filter("waiting", null, null, null, null, null);
        PageWithCounts<Approval> p0 = query.page(waiting, 0, 25, null);
        PageWithCounts<Approval> p1 = query.page(waiting, 1, 25, null);
        assertThat(p0.totalElements()).isEqualTo(30);
        assertThat(p1.content()).hasSize(5);
        Set<String> ids = new HashSet<>();
        p0.content().forEach(a -> ids.add(a.getId()));
        p1.content().forEach(a -> ids.add(a.getId()));
        assertThat(ids).hasSize(30);
        assertThat(p0.counts()).isEqualTo(p1.counts());
        assertThat(p0.counts()).containsEntry("waiting", 30L).containsEntry("owner", 1L).containsEntry("decided", 2L)
                .containsEntry("open", 31L).containsEntry("questions", 1L).containsEntry("all", 32L);
        assertThat(p0.totals().get("waiting")).isEqualByComparingTo("28800");
        assertThat(p0.totals()).isEqualTo(p1.totals());

        assertThat(query.page(new ApprovalQueryService.Filter(null, null, null, "waiting 2", null, null), 0, 25, null).totalElements())
                .isEqualTo(10); // 2, 20-28
        assertThat(query.page(new ApprovalQueryService.Filter("decided", null, null, null, java.time.LocalDate.now().minusDays(300), null), 0, 25, null)
                .totalElements()).isEqualTo(3);
        assertThat(query.page(new ApprovalQueryService.Filter(null, null, null, null, null, null), 0, 5, "amount,desc").content().get(0)
                .getDescription()).isEqualTo("QA PG with the owner");
        assertThatThrownBy(() -> query.page(waiting, 0, 101, null)).isInstanceOf(BadRequestException.class).hasMessage("size is 1 to 100");
        assertThatThrownBy(() -> query.page(new ApprovalQueryService.Filter("nope", null, null, null, null, null), 0, 25, null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> query.page(waiting, 0, 25, "secret")).isInstanceOf(BadRequestException.class);
    }
}

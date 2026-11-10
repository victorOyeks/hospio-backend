package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.dto.AdminResponses.CommissionRow;
import com.hospio.platform.entity.Commission;
import com.hospio.platform.entity.Referral;
import com.hospio.platform.repository.CommissionRepository;
import com.hospio.platform.repository.ReferralRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Referrals and commissions paged in the database: no gaps, counts and money the same on every page, size 101 refused. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.admin.fail-fast=false",
        "hospio.worker.enabled=false",
        "services.internal.api-key=test-key",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-test"
})
class MarketerPagingIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("platform-schema.sql");

    @Autowired MarketerService service;
    @Autowired ReferralRepository referrals;
    @Autowired CommissionRepository commissions;
    @Autowired com.hospio.platform.repository.MarketerProfileRepository profiles;
    @Autowired com.hospio.platform.repository.MarketerRepository marketers;

    String profile;

    @BeforeEach
    void seed() {
        TenantContext.set(Tenant.system("test"));
        commissions.deleteAll();
        referrals.deleteAll();
        marketers.deleteAll();
        profiles.deleteAll();
        profile = profiles.save(com.hospio.platform.entity.MarketerProfile.builder().fullName("QA PG Marketer").email("qa-pg-1@example.com").build()).getId();
        String p2 = profiles.save(com.hospio.platform.entity.MarketerProfile.builder().fullName("Other").email("qa-pg-2@example.com").build()).getId();
        String m1 = marketers.save(com.hospio.platform.entity.Marketer.builder().businessId("b1").userId("u1").fullName("QA PG Marketer").profileId(profile)
                .build()).getId();
        for (int i = 0; i < 48; i++) {
            referrals.save(Referral.builder().profileId(profile).hotelName("QA PG hotel " + i).stage(i % 3 == 0 ? "LIVE" : "LEAD")
                    .sentAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(3600L * i)).build());
        }
        referrals.save(Referral.builder().profileId(p2).hotelName("Someone else's").build());
        for (int i = 0; i < 30; i++) {
            commissions.save(Commission.builder().businessId("b" + i).marketerId(m1).profileId(profile).month(String.format("2026-%02d", 1 + i % 10))
                    .baseAmount(new BigDecimal("1000")).gross(new BigDecimal("100")).wht(new BigDecimal("5")).net(new BigDecimal("95"))
                    .status(i < 10 ? "PAID" : "DUE").build());
        }
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void referralsPageNewestFirstWithStageCounts() {
        PageWithCounts<Referral> p0 = service.referrals(profile, null, 0, 25);
        PageWithCounts<Referral> p1 = service.referrals(profile, null, 1, 25);
        assertThat(p0.totalElements()).isEqualTo(48);
        assertThat(p0.content().get(0).getHotelName()).isEqualTo("QA PG hotel 47");
        assertThat(p1.content()).hasSize(23);
        Set<String> ids = new HashSet<>();
        p0.content().forEach(r -> ids.add(r.getId()));
        p1.content().forEach(r -> ids.add(r.getId()));
        assertThat(ids).hasSize(48);
        assertThat(p0.counts()).isEqualTo(p1.counts()).containsEntry("all", 48L).containsEntry("LIVE", 16L).containsEntry("LEAD", 32L);
        assertThat(service.referrals(profile, "live", 0, 25).totalElements()).isEqualTo(16);
        assertThatThrownBy(() -> service.referrals(profile, null, 0, 101)).isInstanceOf(BadRequestException.class).hasMessage("size is 1 to 100");
    }

    @Test
    void commissionsCarryTotalsOverTheFilteredRange() {
        PageWithCounts<CommissionRow> p0 = service.commissions(profile, null, null, 0, 25);
        PageWithCounts<CommissionRow> p1 = service.commissions(profile, null, null, 1, 25);
        assertThat(p0.totalElements()).isEqualTo(30);
        assertThat(p1.content()).hasSize(5);
        assertThat(p0.totals()).isEqualTo(p1.totals());
        assertThat(p0.totals().get("net")).isEqualByComparingTo("2850");
        assertThat(p0.totals().get("due")).isEqualByComparingTo("1900");
        assertThat(p0.totals().get("paid")).isEqualByComparingTo("950");
        assertThat(p0.counts()).containsEntry("DUE", 20L).containsEntry("PAID", 10L);
        PageWithCounts<CommissionRow> due = service.commissions(profile, "due", null, 0, 25);
        assertThat(due.totalElements()).isEqualTo(20);
        assertThat(due.totals().get("net")).isEqualByComparingTo("1900");
        assertThat(due.counts().get("all")).isEqualTo(30);
        assertThat(service.commissions(profile, null, "2026-03", 0, 25).totalElements()).isEqualTo(3);
    }
}

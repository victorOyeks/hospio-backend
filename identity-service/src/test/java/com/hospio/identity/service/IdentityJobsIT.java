package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.identity.entity.RefreshToken;
import com.hospio.identity.entity.RoleGrant;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.InviteRepository;
import com.hospio.identity.repository.RefreshTokenRepository;
import com.hospio.identity.repository.RoleGrantRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/** The hourly and nightly identity jobs against Postgres: guarded updates act once, the clean-up keeps live tokens. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.admin.fail-fast=false",
        "services.internal.api-key=test-key",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-test"
})
class IdentityJobsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("identity-schema.sql");

    @Autowired UserAccountRepository users;
    @Autowired RoleGrantRepository grants;
    @Autowired InviteRepository invites;
    @Autowired RefreshTokenRepository tokens;
    @Autowired RefreshTokenCleanupJob cleanup;
    @Autowired StaffAccountService staff;
    @Autowired UserService userService;

    private UserAccount ada;

    @BeforeEach
    void seed() {
        TenantContext.set(Tenant.system("test"));
        tokens.deleteAll();
        invites.deleteAll();
        grants.deleteAll();
        users.deleteAll();
        ada = users.save(UserAccount.builder().businessId("b1").fullName("Ada Okeke").email("ada-jobs@x.ng").passwordHash("x").build());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theCleanUpDeletesOnlyTokensExpiredOrRevokedOverThirtyDaysAgo() {
        Instant now = Instant.now();
        RefreshToken live = token("live", now.plus(10, ChronoUnit.DAYS), null);
        RefreshToken recentlyRevoked = token("recent", now.plus(10, ChronoUnit.DAYS), now.minus(5, ChronoUnit.DAYS));
        token("old-revoked", now.plus(10, ChronoUnit.DAYS), now.minus(40, ChronoUnit.DAYS));
        token("long-expired", now.minus(31, ChronoUnit.DAYS), null);

        assertThat(cleanup.purge(now.minus(30, ChronoUnit.DAYS))).isEqualTo(2);
        assertThat(tokens.findAll()).extracting(RefreshToken::getId).containsExactlyInAnyOrder(live.getId(), recentlyRevoked.getId());
    }

    @Test
    void aTimedPauseIsResumedOnceHoweverOftenTheJobRuns() {
        ada.setStatus(UserAccount.Status.PAUSED);
        ada.setPausedUntil(Instant.now().minus(1, ChronoUnit.HOURS));
        users.save(ada);

        assertThat(staff.resumeDuePauses()).isEqualTo(1);
        assertThat(staff.resumeDuePauses()).isZero();
        assertThat(users.findById(ada.getId()).orElseThrow().getStatus()).isEqualTo(UserAccount.Status.ACTIVE);
    }

    @Test
    void anExpiredGrantIsEndedOnce() {
        RoleGrant g = RoleGrant.builder().userId(ada.getId()).businessId("b1").hotelId("h1").role(Role.BAR).level(AccessLevel.EDIT)
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS)).build();
        grants.save(g);

        assertThat(userService.endExpiredGrants()).isEqualTo(1);
        assertThat(userService.endExpiredGrants()).isZero();
        assertThat(grants.findByUserId(ada.getId())).isEmpty();
    }

    private RefreshToken token(String hash, Instant expires, Instant revoked) {
        return tokens.save(RefreshToken.builder().userId(ada.getId()).tokenHash(hash + "-" + System.nanoTime()).expiresAt(expires).revokedAt(revoked).build());
    }
}

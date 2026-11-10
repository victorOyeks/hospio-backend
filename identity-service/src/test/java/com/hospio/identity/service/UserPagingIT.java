package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.identity.dto.UserResponse;
import com.hospio.identity.entity.Invite;
import com.hospio.identity.entity.RoleGrant;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.InviteRepository;
import com.hospio.identity.repository.RefreshTokenRepository;
import com.hospio.identity.repository.RoleGrantRepository;
import com.hospio.identity.repository.UserAccountRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** GET /identity/users?page=: the list's filters in the database, pages without gaps, counts the same on every page. */
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
class UserPagingIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("identity-schema.sql");

    @Autowired UserService service;
    @Autowired UserAccountRepository users;
    @Autowired RoleGrantRepository grants;
    @Autowired InviteRepository invites;
    @Autowired RefreshTokenRepository tokens;

    @BeforeEach
    void seed() {
        TenantContext.set(Tenant.system("test"));
        tokens.deleteAll();
        invites.deleteAll();
        grants.deleteAll();
        users.deleteAll();
        // 40 people at hotel h1 (every tenth a manager, every eighth suspended), 8 at h2 only, 1 owner (business-wide), 1 elsewhere.
        for (int i = 0; i < 40; i++) {
            UserAccount u = users.save(UserAccount.builder().businessId("b1").fullName(String.format("QA PG %02d", i)).email("qa-pg-" + i + "@x.ng")
                    .passwordHash("x").status(i % 8 == 0 ? UserAccount.Status.SUSPENDED : UserAccount.Status.ACTIVE).build());
            grant(u, "h1", i % 10 == 0 ? Role.MANAGER : Role.FRONT_OFFICE);
            if (i < 3) {
                invites.save(Invite.builder().userId(u.getId()).businessId("b1").tokenHash("t" + i).expiresAt(Instant.now().plus(1, ChronoUnit.DAYS)).build());
            }
            if (i == 3) {
                invites.save(Invite.builder().userId(u.getId()).businessId("b1").tokenHash("t" + i).expiresAt(Instant.now().minus(1, ChronoUnit.DAYS)).build());
            }
        }
        for (int i = 0; i < 8; i++) {
            UserAccount u = users.save(UserAccount.builder().businessId("b1").fullName("Other hotel " + i).email("qa-pg-h2-" + i + "@x.ng").passwordHash("x").build());
            grant(u, "h2", Role.BAR);
        }
        UserAccount owner = users.save(UserAccount.builder().businessId("b1").fullName("Zed Owner").email("qa-pg-owner@x.ng").phone("08031234567").passwordHash("x").build());
        grant(owner, null, Role.OWNER);
        users.save(UserAccount.builder().businessId("b2").fullName("QA PG elsewhere").email("qa-pg-b2@x.ng").passwordHash("x").build());
    }

    private void grant(UserAccount u, String hotel, Role role) {
        grants.save(RoleGrant.builder().userId(u.getId()).businessId("b1").hotelId(hotel).role(role).level(AccessLevel.FULL).build());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static void signInAs(Role role, String... hotels) {
        HospioUserDetails u = new HospioUserDetails("caller", "caller@x.ng", "Caller", "b1", List.of(hotels), Map.of(role, AccessLevel.FULL),
                role == Role.OWNER ? JwtUtil.SCOPE_BUSINESS : JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        TenantContext.set(new Tenant("b1", hotels.length == 1 ? hotels[0] : null, "caller", "Caller", Set.of(role),
                role == Role.OWNER ? JwtUtil.SCOPE_BUSINESS : JwtUtil.SCOPE_HOTEL));
    }

    @Test
    void ownerPagesTheBusinessAToZWithCountsOnEveryPage() {
        signInAs(Role.OWNER);
        PageWithCounts<UserResponse> p0 = service.page(null, null, null, null, null, 0, 25, null);
        PageWithCounts<UserResponse> p1 = service.page(null, null, null, null, null, 1, 25, null);
        assertThat(p0.totalElements()).isEqualTo(49);
        assertThat(p0.content()).hasSize(25);
        assertThat(p1.content()).hasSize(24);
        Set<String> ids = new HashSet<>();
        p0.content().forEach(u -> ids.add(u.id()));
        p1.content().forEach(u -> ids.add(u.id()));
        assertThat(ids).hasSize(49);
        assertThat(p0.content().get(0).fullName()).isEqualTo("Other hotel 0");
        assertThat(p0.counts()).isEqualTo(p1.counts());
        assertThat(p0.counts()).containsEntry("all", 49L).containsEntry("SUSPENDED", 5L).containsEntry("ACTIVE", 44L).containsEntry("MANAGER", 4L)
                .containsEntry("INVITE_PENDING", 3L).containsEntry("INVITE_EXPIRED", 1L);
        assertThat(p0.content().get(0).grants()).isNotEmpty();
    }

    @Test
    void filtersRunInTheDatabase() {
        signInAs(Role.OWNER);
        // h1: its 40 people plus the owner's business-wide grant.
        assertThat(service.page(null, "h1", null, null, null, 0, 25, null).totalElements()).isEqualTo(41);
        assertThat(service.page(null, null, Role.MANAGER, null, null, 0, 25, null).totalElements()).isEqualTo(4);
        assertThat(service.page(null, null, null, "suspended", null, 0, 25, null).totalElements()).isEqualTo(5);
        assertThat(service.page(null, null, null, null, "0803", 0, 25, null).content()).extracting(UserResponse::fullName).containsExactly("Zed Owner");
        assertThat(service.page(null, null, null, null, "qa pg 3", 0, 25, "fullName,desc").content()).extracting(UserResponse::fullName)
                .containsExactly("QA PG 39", "QA PG 38", "QA PG 37", "QA PG 36", "QA PG 35", "QA PG 34", "QA PG 33", "QA PG 32", "QA PG 31", "QA PG 30");
        assertThatThrownBy(() -> service.page(null, null, null, null, null, 0, 101, null)).isInstanceOf(BadRequestException.class)
                .hasMessage("size is 1 to 100");
        assertThatThrownBy(() -> service.page(null, null, null, "GONE", null, 0, 25, null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void aManagerSeesOnlyTheirHotelsPeopleLikeTheList() {
        signInAs(Role.MANAGER, "h2");
        PageWithCounts<UserResponse> page = service.page(null, null, null, null, null, 0, 100, null);
        assertThat(page.totalElements()).isEqualTo(service.list(null, null, null, null).size());
        assertThat(page.totalElements()).isEqualTo(8);
    }

    @Test
    void theOwnersStaffTabsAndTheRoleCardsAreCountedInTheDatabase() {
        signInAs(Role.OWNER);
        // Staff only: the owner is left out. Tabs: held (suspended), invited (invite not accepted, not held), working (the rest).
        PageWithCounts<UserResponse> all = service.page(null, null, null, null, null, 0, 25, null, true, false);
        assertThat(all.totalElements()).isEqualTo(48);
        assertThat(all.counts()).containsEntry("all", 48L).containsEntry("HELD", 5L).containsEntry("INVITED", 3L).containsEntry("WORKING", 40L);
        assertThat(service.page(null, null, null, "invited", null, 0, 25, null, true, false).content()).extracting(UserResponse::fullName)
                .containsExactly("QA PG 01", "QA PG 02", "QA PG 03");
        assertThat(service.page(null, null, null, "held", null, 0, 25, null, true, false).totalElements()).isEqualTo(5);
        assertThat(service.page(null, "h2", null, "working", null, 0, 25, null, true, false).totalElements()).isEqualTo(8);
        // A role with a hotel is the role held there.
        assertThat(service.page(null, "h2", Role.FRONT_OFFICE, null, null, 0, 25, null).totalElements()).isZero();
        assertThat(service.page(null, "h1", Role.FRONT_OFFICE, null, null, 0, 25, null).totalElements()).isEqualTo(36);
        PageWithCounts<UserResponse> roles = service.page(null, "h1", null, null, null, 0, 25, null, false, true);
        assertThat(roles.counts()).containsEntry("ROLE_FRONT_OFFICE", 36L).containsEntry("ROLE_MANAGER", 4L).containsEntry("ROLE_OWNER", 1L)
                .containsEntry("ROLE_BAR", 0L);
        assertThat(service.page(null, null, null, null, null, 0, 25, null).counts()).doesNotContainKey("ROLE_BAR");
    }
}

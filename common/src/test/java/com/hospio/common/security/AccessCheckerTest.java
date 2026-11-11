package com.hospio.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.tenancy.Tenant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/** A support-access token (OWNER:VIEW with {@code act}) and an all-VIEW token may look but never write. */
class AccessCheckerTest {

    private final AccessChecker access = new AccessChecker();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(HospioUserDetails user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private static JwtUtil util() {
        JwtUtil util = new JwtUtil();
        ReflectionTestUtils.setField(util, "secret", "test-secret-test-secret-test-secret-test-secret");
        ReflectionTestUtils.setField(util, "expirationMs", 60_000L);
        util.buildParser();
        return util;
    }

    @Test
    void supportAccessTokenCarriesActAndIsReadOnly() {
        JwtUtil util = util();
        String token = util.generateToken("admin1", "kola@hospio360.com", "Kola", "b1", List.of("h1", "h2"),
                Map.of(Role.OWNER, AccessLevel.VIEW), JwtUtil.SCOPE_BUSINESS, "admin1", 30 * 60_000L);

        HospioUserDetails user = util.extractUserDetails(token);
        assertThat(user.getActedBy()).isEqualTo("admin1");
        assertThat(user.getBusinessId()).isEqualTo("b1");
        assertThat(user.getHotelIds()).containsExactly("h1", "h2");
        assertThat(user.isPlatform()).isFalse();
        assertThat(user.isReadOnly()).isTrue();

        signIn(user);
        assertThat(access.owner()).isFalse();
        assertThat(access.manager()).isFalse();
        assertThat(access.atLeast("FRONT_OFFICE", "EDIT")).isFalse();
        assertThat(access.atLeast("FRONT_OFFICE", "FULL")).isFalse();
        assertThat(access.atLeast("FRONT_OFFICE", "VIEW")).isTrue();
        assertThat(access.has("OWNER")).isTrue();
        assertThat(access.managerView()).isTrue();
        assertThat(access.platform()).isFalse();

        Tenant tenant = Tenant.fromUser(user, null);
        assertThat(tenant.actorName()).isEqualTo("Hospio360 · Kola");
        assertThat(tenant.scope()).isEqualTo(JwtUtil.SCOPE_BUSINESS);
    }

    @Test
    void ordinaryTokenWithoutActIsNotReadOnly() {
        HospioUserDetails user = util().extractUserDetails(util().generateToken("o1", "o@x.ng", "Owner", "b1", List.of("h1"),
                Map.of(Role.OWNER, AccessLevel.FULL), JwtUtil.SCOPE_BUSINESS));
        assertThat(user.getActedBy()).isNull();
        assertThat(user.isReadOnly()).isFalse();
        signIn(user);
        assertThat(access.owner()).isTrue();
        assertThat(access.manager()).isTrue();
        assertThat(access.atLeast("BAR", "FULL")).isTrue();
    }

    @Test
    void allViewGrantsAreReadOnlyButAMixedTokenIsNot() {
        HospioUserDetails viewer = new HospioUserDetails("s1", "s@x.ng", "S", "b1", List.of("h1"),
                Map.of(Role.FRONT_OFFICE, AccessLevel.VIEW, Role.BAR, AccessLevel.VIEW), JwtUtil.SCOPE_HOTEL);
        assertThat(viewer.isReadOnly()).isTrue();
        signIn(viewer);
        assertThat(access.atLeast("FRONT_OFFICE", "EDIT")).isFalse();
        assertThat(access.atLeast("FRONT_OFFICE", "VIEW")).isTrue();

        HospioUserDetails mixed = new HospioUserDetails("s2", "s2@x.ng", "S2", "b1", List.of("h1"),
                Map.of(Role.FRONT_OFFICE, AccessLevel.VIEW, Role.BAR, AccessLevel.EDIT), JwtUtil.SCOPE_HOTEL);
        assertThat(mixed.isReadOnly()).isFalse();
        signIn(mixed);
        assertThat(access.atLeast("BAR", "EDIT")).isTrue();
        assertThat(access.atLeast("FRONT_OFFICE", "EDIT")).isFalse();
    }
}

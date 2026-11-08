package com.hospio.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtUtilTest {

    private JwtUtil util() {
        JwtUtil util = new JwtUtil();
        ReflectionTestUtils.setField(util, "secret", "test-secret-test-secret-test-secret-test-secret");
        ReflectionTestUtils.setField(util, "expirationMs", 60_000L);
        util.buildParser();
        return util;
    }

    @Test
    void tokenCarriesBusinessHotelsRolesAndLevels() {
        JwtUtil util = util();
        String token = util.generateToken("u1", "ada@x.ng", "Ada Obi", "b1", List.of("h1", "h2"),
                Map.of(Role.FRONT_OFFICE, AccessLevel.EDIT, Role.BAR, AccessLevel.VIEW), JwtUtil.SCOPE_HOTEL);

        assertThat(util.isTokenValid(token)).isTrue();
        HospioUserDetails user = util.extractUserDetails(token);
        assertThat(user.getUserId()).isEqualTo("u1");
        assertThat(user.getBusinessId()).isEqualTo("b1");
        assertThat(user.getHotelIds()).containsExactly("h1", "h2");
        assertThat(user.has(Role.FRONT_OFFICE, AccessLevel.EDIT)).isTrue();
        assertThat(user.has(Role.FRONT_OFFICE, AccessLevel.FULL)).isFalse();
        assertThat(user.has(Role.BAR, AccessLevel.VIEW)).isTrue();
        assertThat(user.isPlatform()).isFalse();
        assertThat(user.getAuthorities()).extracting("authority").containsExactlyInAnyOrder("ROLE_FRONT_OFFICE", "ROLE_BAR");
    }

    @Test
    void platformTokenHasNoBusiness() {
        JwtUtil util = util();
        String token = util.generateToken("admin", "a@hospio360.com", "Amoria", null, List.of(),
                Map.of(Role.SUPER_ADMIN, AccessLevel.FULL), JwtUtil.SCOPE_PLATFORM);
        HospioUserDetails user = util.extractUserDetails(token);
        assertThat(user.getBusinessId()).isNull();
        assertThat(user.isPlatform()).isTrue();
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtUtil util = util();
        String token = util.generateToken("u1", "a@b", "A", "b1", List.of(), Map.of(Role.OWNER, AccessLevel.FULL), JwtUtil.SCOPE_BUSINESS);
        assertThat(util.isTokenValid(token + "x")).isFalse();
    }
}

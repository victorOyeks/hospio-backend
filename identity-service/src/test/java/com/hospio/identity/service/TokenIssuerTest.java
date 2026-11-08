package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.identity.client.TenantClient;
import com.hospio.identity.dto.AuthResponse;
import com.hospio.identity.entity.RoleGrant;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RefreshTokenRepository;
import com.hospio.identity.repository.RoleGrantRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** An owner's token carries every hotel of the business; marketers are not the platform; staff keep their grant hotels. */
class TokenIssuerTest {

    private final RoleGrantRepository grants = mock(RoleGrantRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final TenantClient tenant = mock(TenantClient.class);
    private final JwtUtil jwtUtil = new JwtUtil();
    private final TokenIssuer issuer = new TokenIssuer(jwtUtil, grants, refreshTokens, tenant);

    TokenIssuerTest() {
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-secret-test-secret-test-secret-test-secret");
        ReflectionTestUtils.setField(jwtUtil, "expirationMs", 60_000L);
        ReflectionTestUtils.invokeMethod(jwtUtil, "buildParser");
        ReflectionTestUtils.setField(issuer, "refreshDays", 30L);
        when(refreshTokens.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static UserAccount user(String id, String businessId) {
        UserAccount u = UserAccount.builder().businessId(businessId).fullName("Someone").email(id + "@x.ng").build();
        u.setId(id);
        return u;
    }

    private static RoleGrant grant(String userId, Role role, String hotelId) {
        return RoleGrant.builder().userId(userId).role(role).level(AccessLevel.FULL).hotelId(hotelId).build();
    }

    @Test
    void ownerTokenCarriesEveryHotelOfTheBusinessWithBusinessScope() {
        when(grants.findByUserId("o1")).thenReturn(List.of(grant("o1", Role.OWNER, null)));
        when(tenant.hotelIds("b1")).thenReturn(Optional.of(List.of("h1", "h2", "h3")));

        AuthResponse response = issuer.issue(user("o1", "b1"));
        HospioUserDetails details = jwtUtil.extractUserDetails(response.accessToken());

        assertThat(details.getScope()).isEqualTo(JwtUtil.SCOPE_BUSINESS);
        assertThat(details.getHotelIds()).containsExactly("h1", "h2", "h3");
        assertThat(details.getBusinessId()).isEqualTo("b1");
        assertThat(response.roles()).containsExactly("OWNER");
    }

    @Test
    void ownerFallsBackToGrantHotelsWhenTenantIsDown() {
        when(grants.findByUserId("o1")).thenReturn(List.of(grant("o1", Role.OWNER, null), grant("o1", Role.MANAGER, "h9")));
        when(tenant.hotelIds("b1")).thenReturn(Optional.empty());

        HospioUserDetails details = jwtUtil.extractUserDetails(issuer.issue(user("o1", "b1")).accessToken());

        assertThat(details.getScope()).isEqualTo(JwtUtil.SCOPE_BUSINESS);
        assertThat(details.getHotelIds()).containsExactly("h9");
    }

    @Test
    void staffKeepTheirGrantHotelsAndNeverAskTenant() {
        when(grants.findByUserId("s1")).thenReturn(List.of(grant("s1", Role.FRONT_OFFICE, "h1"), grant("s1", Role.BAR, "h2")));

        HospioUserDetails details = jwtUtil.extractUserDetails(issuer.issue(user("s1", "b1")).accessToken());

        assertThat(details.getScope()).isEqualTo(JwtUtil.SCOPE_HOTEL);
        assertThat(details.getHotelIds()).containsExactly("h1", "h2");
        verify(tenant, never()).hotelIds(any());
    }

    @Test
    void marketerIsNotThePlatform() {
        when(grants.findByUserId("m1")).thenReturn(List.of(grant("m1", Role.MARKETER, null)));

        HospioUserDetails details = jwtUtil.extractUserDetails(issuer.issue(user("m1", null)).accessToken());

        assertThat(details.getScope()).isEqualTo(JwtUtil.SCOPE_MARKETER);
        assertThat(details.isPlatform()).isFalse();
        assertThat(details.isMarketer()).isTrue();
    }

    @Test
    void platformStaffStayPlatform() {
        when(grants.findByUserId("a1")).thenReturn(List.of(grant("a1", Role.SUPPORT, null), grant("a1", Role.MARKETER, null)));

        HospioUserDetails details = jwtUtil.extractUserDetails(issuer.issue(user("a1", null)).accessToken());

        assertThat(details.getScope()).isEqualTo(JwtUtil.SCOPE_PLATFORM);
        assertThat(details.isPlatform()).isTrue();
    }
}

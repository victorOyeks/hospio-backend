package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.GoneException;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.identity.client.TenantClient;
import com.hospio.identity.dto.SupportAccessTokenResponse;
import com.hospio.identity.entity.SupportAccessGrant;
import com.hospio.identity.repository.SupportAccessGrantRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/** The support token is the admin acting as a read-only owner of the business, capped at 60 minutes, only for their own grant. */
class SupportAccessServiceTest {

    private final SupportAccessGrantRepository grants = mock(SupportAccessGrantRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final TenantClient tenant = mock(TenantClient.class);
    private final JwtUtil jwtUtil = new JwtUtil();
    private final SupportAccessService service = new SupportAccessService(grants, users, tenant, jwtUtil);

    SupportAccessServiceTest() {
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-secret-test-secret-test-secret-test-secret");
        ReflectionTestUtils.setField(jwtUtil, "expirationMs", 3_600_000L);
        ReflectionTestUtils.invokeMethod(jwtUtil, "buildParser");
        ReflectionTestUtils.setField(service, "maxTokenMinutes", 60L);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signInAs(String id, Role role) {
        HospioUserDetails admin = new HospioUserDetails(id, id + "@hospio360.com", "Kola Adeyemi", null, List.of(),
                Map.of(role, AccessLevel.FULL), JwtUtil.SCOPE_PLATFORM);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
    }

    private static SupportAccessGrant grant(String adminId, Instant expiresAt) {
        SupportAccessGrant g = SupportAccessGrant.builder().adminId(adminId).businessId("b1").reason("Owner asked about a bill").expiresAt(expiresAt).build();
        g.setId("g1");
        return g;
    }

    @Test
    void tokenIsAReadOnlyOwnerOfTheBusinessActingForTheAdmin() {
        signInAs("admin1", Role.SUPPORT);
        when(grants.findById("g1")).thenReturn(Optional.of(grant("admin1", Instant.now().plus(4, ChronoUnit.HOURS))));
        when(tenant.hotelIds("b1")).thenReturn(Optional.of(List.of("h1", "h2")));

        SupportAccessTokenResponse response = service.token("g1");
        HospioUserDetails user = jwtUtil.extractUserDetails(response.accessToken());

        assertThat(user.getUserId()).isEqualTo("admin1");
        assertThat(user.getFullName()).isEqualTo("Kola Adeyemi");
        assertThat(user.getBusinessId()).isEqualTo("b1");
        assertThat(user.getHotelIds()).containsExactly("h1", "h2");
        assertThat(user.getRoles()).containsExactly(Map.entry(Role.OWNER, AccessLevel.VIEW));
        assertThat(user.getScope()).isEqualTo(JwtUtil.SCOPE_BUSINESS);
        assertThat(user.getActedBy()).isEqualTo("admin1");
        assertThat(user.isReadOnly()).isTrue();
        assertThat(user.isPlatform()).isFalse();
        assertThat(response.hotels()).containsExactly("h1", "h2");
        // a four-hour grant still gives a token of at most 60 minutes
        assertThat(response.expiresIn()).isBetween(3_500L, 3_600L);
        assertThat(jwtUtil.extractAllClaims(response.accessToken()).getExpiration().toInstant())
                .isBefore(Instant.now().plus(61, ChronoUnit.MINUTES));
    }

    @Test
    void shortGrantShortensTheToken() {
        signInAs("admin1", Role.SUPPORT);
        when(grants.findById("g1")).thenReturn(Optional.of(grant("admin1", Instant.now().plus(10, ChronoUnit.MINUTES))));
        when(tenant.hotelIds("b1")).thenReturn(Optional.of(List.of("h1")));

        assertThat(service.token("g1").expiresIn()).isBetween(500L, 600L);
    }

    @Test
    void expiredGrantIsGoneAndSomeoneElsesGrantIsForbidden() {
        signInAs("admin1", Role.SUPPORT);
        when(grants.findById("g1")).thenReturn(Optional.of(grant("admin1", Instant.now().minusSeconds(1))));
        assertThatThrownBy(() -> service.token("g1")).isInstanceOf(GoneException.class);

        when(grants.findById("g1")).thenReturn(Optional.of(grant("admin2", Instant.now().plusSeconds(600))));
        assertThatThrownBy(() -> service.token("g1")).isInstanceOf(ForbiddenException.class);
    }
}

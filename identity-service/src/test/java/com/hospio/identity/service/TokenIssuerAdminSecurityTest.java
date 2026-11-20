package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.UnauthorizedException;
import com.hospio.common.security.JwtUtil;
import com.hospio.identity.client.PlatformClient;
import com.hospio.identity.client.TenantClient;
import com.hospio.identity.dto.AuthResponse;
import com.hospio.identity.entity.RefreshToken;
import com.hospio.identity.entity.RoleGrant;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RefreshTokenRepository;
import com.hospio.identity.repository.RoleGrantRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The admin portal's security settings on the tokens identity issues (docs/sign-in.md, "Admin security settings"): "Require 2-step
 * sign-in for every admin" limits a staff member without 2-step to setting it up; the session length bounds a staff session from
 * its sign-in. Hotel accounts and marketers are never touched.
 */
class TokenIssuerAdminSecurityTest {

    private final RoleGrantRepository grants = mock(RoleGrantRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final PlatformClient platform = mock(PlatformClient.class);
    private final JwtUtil jwtUtil = new JwtUtil();
    private final AdminSecurityPolicy policy = new AdminSecurityPolicy(platform, 0, 0);
    private final TokenIssuer issuer = new TokenIssuer(jwtUtil, grants, refreshTokens, mock(TenantClient.class), policy);
    private final List<RefreshToken> saved = new ArrayList<>();

    TokenIssuerAdminSecurityTest() {
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-secret-test-secret-test-secret-test-secret");
        ReflectionTestUtils.setField(jwtUtil, "expirationMs", 3_600_000L);
        ReflectionTestUtils.invokeMethod(jwtUtil, "buildParser");
        ReflectionTestUtils.setField(issuer, "refreshDays", 30L);
        when(refreshTokens.save(any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
    }

    private void settings(boolean require2Step, int hours) {
        when(platform.securitySettings()).thenReturn(Optional.of(new PlatformClient.SecuritySettings(require2Step, hours)));
    }

    private UserAccount person(String id, String businessId, boolean twoStep, Role... roles) {
        UserAccount u = UserAccount.builder().businessId(businessId).fullName("Someone").email(id + "@x.ng").build();
        u.setId(id);
        u.setTwoStepEnabled(twoStep);
        List<RoleGrant> list = new ArrayList<>();
        for (Role r : roles) {
            list.add(RoleGrant.builder().userId(id).role(r).level(AccessLevel.FULL).hotelId(businessId == null ? null : "h1").build());
        }
        when(grants.findByUserId(id)).thenReturn(list);
        return u;
    }

    private boolean setupOnly(AuthResponse r) {
        return JwtUtil.twoStepSetupOnly(jwtUtil.extractAllClaims(r.accessToken()));
    }

    private RefreshToken last() {
        return saved.get(saved.size() - 1);
    }

    @Test
    void settingOnAndAdminWithout2StepIsLimitedToSetup() {
        settings(true, 12);
        for (Role role : List.of(Role.SUPER_ADMIN, Role.SUPPORT, Role.FINANCE_ADMIN)) {
            AuthResponse r = issuer.issue(person("a-" + role, null, false, role));
            assertThat(r.twoStepSetupRequired()).as(role.name()).isTrue();
            assertThat(setupOnly(r)).as(role.name()).isTrue();
        }
    }

    @Test
    void limitedSessionStaysLimitedOnRefreshUntil2StepIsOn() {
        settings(true, 12);
        UserAccount admin = person("a1", null, false, Role.SUPPORT);
        issuer.issue(admin);
        RefreshToken first = last();
        assertThat(setupOnly(issuer.continueSession(admin, first, null))).isTrue();

        admin.setTwoStepEnabled(true);
        AuthResponse after = issuer.continueSession(admin, first, null);
        assertThat(after.twoStepSetupRequired()).isFalse();
        assertThat(setupOnly(after)).isFalse();
    }

    @Test
    void settingOnAndAdminWith2StepIsNormal() {
        settings(true, 12);
        AuthResponse r = issuer.issue(person("a1", null, true, Role.SUPER_ADMIN));
        assertThat(r.twoStepSetupRequired()).isFalse();
        assertThat(setupOnly(r)).isFalse();
    }

    @Test
    void settingOffChangesNothing() {
        settings(false, 12);
        AuthResponse r = issuer.issue(person("a1", null, false, Role.SUPER_ADMIN));
        assertThat(r.twoStepSetupRequired()).isFalse();
        assertThat(setupOnly(r)).isFalse();
    }

    @Test
    void hotelStaffAndMarketersAreUnaffected() {
        settings(true, 4);
        for (UserAccount u : List.of(person("o1", "b1", false, Role.OWNER), person("m1", "b1", false, Role.MANAGER),
                person("f1", "b1", false, Role.FRONT_OFFICE, Role.FINANCE), person("k1", null, false, Role.MARKETER))) {
            Instant before = Instant.now();
            AuthResponse r = issuer.issue(u);
            assertThat(r.twoStepSetupRequired()).as(u.getId()).isFalse();
            assertThat(setupOnly(r)).as(u.getId()).isFalse();
            // the usual 30 days and a full hour, not the 4 h staff session
            assertThat(last().getExpiresAt()).as(u.getId()).isAfter(before.plus(29, ChronoUnit.DAYS));
            assertThat(r.expiresIn()).isEqualTo(3600);
        }
    }

    @Test
    void settingUnreadableKeepsTheLastValue() {
        settings(true, 4);
        assertThat(issuer.issue(person("a1", null, false, Role.SUPPORT)).twoStepSetupRequired()).isTrue();
        when(platform.securitySettings()).thenReturn(Optional.empty());
        Instant before = Instant.now();
        AuthResponse r = issuer.issue(person("a2", null, false, Role.SUPPORT));
        assertThat(r.twoStepSetupRequired()).isTrue();
        assertThat(last().getExpiresAt()).isCloseTo(before.plus(4, ChronoUnit.HOURS), within(5, ChronoUnit.SECONDS));
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 12, 24})
    void staffSessionLastsTheSessionLength(int hours) {
        settings(false, hours);
        Instant before = Instant.now();
        AuthResponse r = issuer.issue(person("a1", null, true, Role.SUPER_ADMIN));
        assertThat(last().getExpiresAt()).isCloseTo(before.plus(hours, ChronoUnit.HOURS), within(5, ChronoUnit.SECONDS));
        assertThat(r.expiresIn()).isEqualTo(3600);
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 12, 24})
    void refreshKeepsTheSignInTimeAndTheAccessTokenNeverOutlivesTheSession(int hours) {
        settings(false, hours);
        UserAccount admin = person("a1", null, true, Role.FINANCE_ADMIN);
        Instant signedIn = Instant.now().minus(hours, ChronoUnit.HOURS).plus(20, ChronoUnit.MINUTES);
        RefreshToken previous = RefreshToken.builder().userId("a1").sessionId("s1").familyId("s1").signedInAt(signedIn)
                .expiresAt(Instant.now().plusSeconds(60)).build();

        AuthResponse r = issuer.continueSession(admin, previous, null);

        assertThat(last().getSignedInAt()).isEqualTo(signedIn);
        assertThat(last().getExpiresAt()).isEqualTo(signedIn.plus(hours, ChronoUnit.HOURS));
        // 20 minutes left: the access token lasts 20 minutes, not an hour
        assertThat(r.expiresIn()).isBetween(Duration.ofMinutes(19).toSeconds(), Duration.ofMinutes(20).toSeconds());
        Instant exp = jwtUtil.extractAllClaims(r.accessToken()).getExpiration().toInstant();
        assertThat(exp).isBeforeOrEqualTo(signedIn.plus(hours, ChronoUnit.HOURS).plusSeconds(1));
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 12, 24})
    void refreshPastTheSessionLengthIsRefused(int hours) {
        settings(false, hours);
        UserAccount admin = person("a1", null, true, Role.SUPPORT);
        RefreshToken previous = RefreshToken.builder().userId("a1").sessionId("s1")
                .signedInAt(Instant.now().minus(hours, ChronoUnit.HOURS).minusSeconds(1)).expiresAt(Instant.now().plusSeconds(60)).build();

        assertThatThrownBy(() -> issuer.continueSession(admin, previous, null))
                .isInstanceOf(UnauthorizedException.class).hasMessage(TokenIssuer.SESSION_ENDED);
    }

    @Test
    void shorterSettingEndsLongerSessionsAtTheirNextRefresh() {
        settings(false, 4);
        UserAccount admin = person("a1", null, true, Role.SUPER_ADMIN);
        RefreshToken signedIn5HoursAgo = RefreshToken.builder().userId("a1").sessionId("s1")
                .signedInAt(Instant.now().minus(5, ChronoUnit.HOURS)).expiresAt(Instant.now().plus(7, ChronoUnit.HOURS)).build();
        assertThatThrownBy(() -> issuer.continueSession(admin, signedIn5HoursAgo, null)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void hotelSessionsHaveNoSessionLength() {
        settings(false, 4);
        UserAccount manager = person("m1", "b1", false, Role.MANAGER);
        RefreshToken old = RefreshToken.builder().userId("m1").sessionId("s1")
                .signedInAt(Instant.now().minus(10, ChronoUnit.DAYS)).expiresAt(Instant.now().plusSeconds(60)).build();
        AuthResponse r = issuer.continueSession(manager, old, null);
        assertThat(r.expiresIn()).isEqualTo(3600);
    }

    @Test
    void handOverKeepsTheFamilysSignInTime() {
        settings(false, 4);
        UserAccount admin = person("a1", null, true, Role.SUPPORT);
        Instant familyStart = Instant.now().minus(3, ChronoUnit.HOURS);
        when(refreshTokens.findByUserIdAndRevokedAtIsNull("a1")).thenReturn(List.of(
                RefreshToken.builder().userId("a1").sessionId("s1").familyId("fam").signedInAt(familyStart).expiresAt(Instant.now().plusSeconds(600)).build()));

        issuer.issueInFamily(admin, "fam");

        assertThat(last().getSignedInAt()).isEqualTo(familyStart);
        assertThat(last().getExpiresAt()).isEqualTo(familyStart.plus(4, ChronoUnit.HOURS));
    }

    @Test
    void tokensCarryNoSetupClaimWhenNotLimited() {
        settings(true, 12);
        AuthResponse r = issuer.issue(person("o1", "b1", false, Role.OWNER));
        assertThat(jwtUtil.extractAllClaims(r.accessToken()).get(JwtUtil.CLAIM_SETUP)).isNull();
    }
}

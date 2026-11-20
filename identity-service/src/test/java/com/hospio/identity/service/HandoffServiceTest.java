package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.UnauthorizedException;
import com.hospio.identity.dto.AuthResponse;
import com.hospio.identity.dto.HandoffDtos;
import com.hospio.identity.entity.HandoffCode;
import com.hospio.identity.entity.RefreshToken;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.HandoffCodeRepository;
import com.hospio.identity.repository.RefreshTokenRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The one-time pass between the hotel app and the admin console (docs/sign-in.md): only the hash is stored, the code works once
 * (replay and racing requests refused), runs out, is bound to the target app and the person, never opens an app the person has
 * no role on, dies with the session it came from, and the target origin comes from configuration only.
 */
class HandoffServiceTest {

    private final HandoffCodeRepository codes = mock(HandoffCodeRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final TokenIssuer tokenIssuer = mock(TokenIssuer.class);
    private HandoffService service;

    /** The table: by id, as the database would keep it. */
    private final Map<String, HandoffCode> rows = new HashMap<>();
    private final Map<String, Map<Role, AccessLevel>> roles = new HashMap<>();
    private final List<RefreshToken> sessionTokens = new ArrayList<>();
    private Instant now = Instant.parse("2026-11-20T10:00:00Z");

    private final UserAccount kola = UserAccount.builder().id("kola").fullName("Kola").email("kola@x.ng").status(UserAccount.Status.ACTIVE).build();
    private final UserAccount ada = UserAccount.builder().id("ada").businessId("b1").fullName("Ada").email("ada@x.ng").status(UserAccount.Status.ACTIVE).build();

    @BeforeEach
    void setUp() {
        service = new HandoffService(codes, users, refreshTokens, tokenIssuer,
                "https://web-staging.hospio360.com/some/path?x=1", "https://admin-staging.hospio360.com", 30);
        service.clock = Clock.fixed(now, ZoneOffset.UTC);
        roles.put("kola", new EnumMap<>(Map.of(Role.SUPPORT, AccessLevel.FULL)));
        roles.put("ada", new EnumMap<>(Map.of(Role.OWNER, AccessLevel.FULL)));
        when(users.findById("kola")).thenReturn(Optional.of(kola));
        when(users.findById("ada")).thenReturn(Optional.of(ada));
        when(users.lockById("kola")).thenReturn(Optional.of(kola));
        when(users.lockById("ada")).thenReturn(Optional.of(ada));
        when(tokenIssuer.rolesAt(anyString(), any())).thenAnswer(i -> roles.getOrDefault(i.getArgument(0, String.class), Map.of()));
        when(tokenIssuer.issue(any(UserAccount.class))).thenAnswer(i -> {
            UserAccount u = i.getArgument(0);
            return new AuthResponse("access-" + u.getId(), "refresh-" + u.getId(), 3600, List.of("X"), false, false);
        });
        when(tokenIssuer.issueInFamily(any(UserAccount.class), anyString())).thenAnswer(i -> {
            UserAccount u = i.getArgument(0);
            return new AuthResponse("access-" + u.getId(), "refresh-" + u.getId(), 3600, List.of("X"), false, false);
        });
        when(refreshTokens.findBySessionIdAndRevokedAtIsNull(anyString())).thenReturn(sessionTokens);
        when(codes.save(any(HandoffCode.class))).thenAnswer(i -> {
            HandoffCode c = i.getArgument(0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID().toString());
            }
            rows.put(c.getId(), c);
            return c;
        });
        when(codes.findByCodeHash(anyString())).thenAnswer(i -> rows.values().stream()
                .filter(c -> c.getCodeHash().equals(i.getArgument(0))).findFirst()
                // a copy, as a fresh read from the database would be
                .map(c -> HandoffCode.builder().id(c.getId()).codeHash(c.getCodeHash()).userId(c.getUserId()).target(c.getTarget())
                        .nextPath(c.getNextPath()).sessionId(c.getSessionId()).familyId(c.getFamilyId()).expiresAt(c.getExpiresAt()).usedAt(c.getUsedAt()).build()));
        when(codes.markUsed(anyString(), any(Instant.class))).thenAnswer(i -> {
            HandoffCode c = rows.get(i.getArgument(0, String.class));
            if (c == null || c.getUsedAt() != null) {
                return 0;
            }
            c.setUsedAt(i.getArgument(1));
            return 1;
        });
    }

    private String codeOf(HandoffDtos.Issued issued) {
        return issued.url().substring(issued.url().indexOf("code=") + 5);
    }

    private HandoffDtos.Issued issue(String user, String target, String next) {
        return service.issue(user, "sid-" + user, null, new HandoffDtos.Issue(target, next, false));
    }

    private void later(long seconds) {
        now = now.plusSeconds(seconds);
        service.clock = Clock.fixed(now, ZoneOffset.UTC);
    }

    @Test
    void codeIsLongRandomStoredHashedAndPointsAtTheConfiguredOriginOnly() {
        HandoffDtos.Issued issued = issue("kola", "admin", "/dashboard/support");
        String code = codeOf(issued);

        assertThat(issued.url()).startsWith("https://admin-staging.hospio360.com/auth/handoff?code=");
        assertThat(issued.expiresIn()).isEqualTo(30);
        // 32 random bytes, base64url: 43 characters, 256 bits
        assertThat(code).hasSize(43).matches("[A-Za-z0-9_-]+");
        HandoffCode row = rows.values().iterator().next();
        assertThat(row.getCodeHash()).isEqualTo(Hashing.sha256(code)).isNotEqualTo(code);
        assertThat(row.getUserId()).isEqualTo("kola");
        assertThat(row.getTarget()).isEqualTo("admin");
        assertThat(row.getExpiresAt()).isEqualTo(now.plusSeconds(30));
        // two codes are never the same
        assertThat(codeOf(issue("kola", "admin", null))).isNotEqualTo(code);
        // a configured address with a path keeps only its origin
        assertThat(issue("ada", "hotel", null).url()).startsWith("https://web-staging.hospio360.com/auth/handoff?code=");
    }

    @Test
    void exchangeSignsInTheBoundPersonOnTheTargetAppWithTheNextPage() {
        String code = codeOf(issue("kola", "admin", "/dashboard/support?tab=open"));

        HandoffDtos.Exchanged out = service.exchange(new HandoffDtos.Exchange(code, "admin"));

        assertThat(out.accessToken()).isEqualTo("access-kola");
        assertThat(out.refreshToken()).isEqualTo("refresh-kola");
        assertThat(out.next()).isEqualTo("/dashboard/support?tab=open");
        verify(tokenIssuer).issueInFamily(kola, "sid-kola");
    }

    @Test
    void theSessionOpenedByTheCodeJoinsTheAskingBrowsersFamily() {
        sessionTokens.add(RefreshToken.builder().id("t1").userId("kola").sessionId("sid-kola").familyId("fam-1").tokenHash("h")
                .expiresAt(now.plusSeconds(999)).build());
        when(tokenIssuer.issueInFamily(any(UserAccount.class), anyString()))
                .thenReturn(new AuthResponse("access-fam", "refresh-fam", 3600, List.of("SUPPORT"), false, false));
        String code = codeOf(service.issue("kola", "sid-kola", null, new HandoffDtos.Issue("admin", null, true)));

        assertThat(service.exchange(new HandoffDtos.Exchange(code, "admin")).accessToken()).isEqualTo("access-fam");
        verify(tokenIssuer).issueInFamily(kola, "fam-1");
    }

    @Test
    void replayIsRefused() {
        String code = codeOf(issue("kola", "admin", null));
        service.exchange(new HandoffDtos.Exchange(code, "admin"));

        assertThatThrownBy(() -> service.exchange(new HandoffDtos.Exchange(code, "admin")))
                .isInstanceOf(UnauthorizedException.class).hasMessageContaining("already used");
    }

    @Test
    void twoRacingExchangesSignInOnlyOnce() {
        String code = codeOf(issue("kola", "admin", null));
        // The second request read the row before the first marked it: the database update decides.
        HandoffCode stale = codes.findByCodeHash(Hashing.sha256(code)).orElseThrow();
        service.exchange(new HandoffDtos.Exchange(code, "admin"));
        when(codes.findByCodeHash(Hashing.sha256(code))).thenReturn(Optional.of(stale));

        assertThatThrownBy(() -> service.exchange(new HandoffDtos.Exchange(code, "admin")))
                .isInstanceOf(UnauthorizedException.class).hasMessageContaining("already used");
        verify(tokenIssuer, org.mockito.Mockito.times(1)).issueInFamily(any(UserAccount.class), anyString());
    }

    @Test
    void expiredCodeIsRefusedAndUsedUp() {
        String code = codeOf(issue("kola", "admin", null));
        later(30);

        assertThatThrownBy(() -> service.exchange(new HandoffDtos.Exchange(code, "admin")))
                .isInstanceOf(UnauthorizedException.class).hasMessageContaining("expired");
        verify(tokenIssuer, never()).issueInFamily(any(UserAccount.class), anyString());
        assertThat(rows.values().iterator().next().getUsedAt()).isNotNull();
    }

    @Test
    void codeLivesAtMostSixtySecondsWhateverTheSetting() {
        HandoffService slow = new HandoffService(codes, users, refreshTokens, tokenIssuer, "https://h.example", "https://a.example", 3600);
        slow.clock = Clock.fixed(now, ZoneOffset.UTC);
        assertThat(slow.issue("kola", "sid", null, new HandoffDtos.Issue("admin", null, false)).expiresIn()).isEqualTo(60);
    }

    @Test
    void wrongTargetIsRefusedAndBurnsTheCode() {
        String code = codeOf(issue("kola", "admin", null));

        assertThatThrownBy(() -> service.exchange(new HandoffDtos.Exchange(code, "hotel")))
                .isInstanceOf(UnauthorizedException.class).hasMessageContaining("other app");
        // ... and can't then be used on the right app
        assertThatThrownBy(() -> service.exchange(new HandoffDtos.Exchange(code, "admin")))
                .isInstanceOf(UnauthorizedException.class);
        verify(tokenIssuer, never()).issueInFamily(any(UserAccount.class), anyString());
    }

    @Test
    void unknownTargetIsRefused() {
        assertThatThrownBy(() -> issue("kola", "https://evil.example", null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.exchange(new HandoffDtos.Exchange("x", "landing"))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void noCodeForAnAppThePersonHasNoRoleOn() {
        // wrong user for the target: a hotel owner can't get into the console, support can't get a hotel-app session
        assertThatThrownBy(() -> issue("ada", "admin", null)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> issue("kola", "hotel", null)).isInstanceOf(ForbiddenException.class);
        roles.put("ese", new EnumMap<>(Map.of(Role.MARKETER, AccessLevel.FULL)));
        UserAccount ese = UserAccount.builder().id("ese").fullName("Ese").email("e@x").status(UserAccount.Status.ACTIVE).build();
        when(users.findById("ese")).thenReturn(Optional.of(ese));
        assertThat(issue("ese", "admin", null).url()).contains("admin-staging");
    }

    @Test
    void codeSignsInOnlyThePersonItWasIssuedTo() {
        String adaCode = codeOf(issue("ada", "hotel", null));
        String kolaCode = codeOf(issue("kola", "admin", null));

        assertThat(service.exchange(new HandoffDtos.Exchange(adaCode, "hotel")).accessToken()).isEqualTo("access-ada");
        assertThat(service.exchange(new HandoffDtos.Exchange(kolaCode, "admin")).accessToken()).isEqualTo("access-kola");
    }

    @Test
    void personWhoLostTheRoleMeanwhileIsRefused() {
        String code = codeOf(issue("kola", "admin", null));
        roles.put("kola", Map.of());

        assertThatThrownBy(() -> service.exchange(new HandoffDtos.Exchange(code, "admin")))
                .isInstanceOf(ForbiddenException.class).hasMessageContaining("no longer");
    }

    @Test
    void pausedAccountIsRefused() {
        String code = codeOf(issue("ada", "hotel", null));
        ada.setStatus(UserAccount.Status.PAUSED);

        assertThatThrownBy(() -> service.exchange(new HandoffDtos.Exchange(code, "hotel"))).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void signingOutBeforeTheExchangeVoidsTheCode() {
        String code = codeOf(issue("kola", "admin", null));
        when(refreshTokens.sessionEnded("sid-kola")).thenReturn(true);

        assertThatThrownBy(() -> service.exchange(new HandoffDtos.Exchange(code, "admin")))
                .isInstanceOf(UnauthorizedException.class).hasMessageContaining("signed out");
    }

    @Test
    void wrongAppSignInEndsItsOwnSessionAndTheCodeStillWorks() {
        RefreshToken t = RefreshToken.builder().id("t1").userId("kola").sessionId("sid-kola").tokenHash("h").expiresAt(now.plusSeconds(999)).build();
        sessionTokens.add(t);

        HandoffDtos.Issued issued = service.issue("kola", "sid-kola", null, new HandoffDtos.Issue("admin", null, true));

        assertThat(t.getRevokedAt()).isNotNull();
        assertThat(t.getEndedAt()).isNotNull();
        assertThat(rows.values().iterator().next().getSessionId()).isNull();
        assertThat(service.exchange(new HandoffDtos.Exchange(codeOf(issued), "admin")).accessToken()).isEqualTo("access-kola");
    }

    @Test
    void supportViewAndSharedDeviceSessionsCannotHandOff() {
        assertThatThrownBy(() -> service.issue("kola", "sid", "admin-1", new HandoffDtos.Issue("admin", null, false)))
                .isInstanceOf(ForbiddenException.class);
        sessionTokens.add(RefreshToken.builder().id("t").userId("ada").sessionId("sid-ada").deviceId("dev").tokenHash("h")
                .expiresAt(now.plusSeconds(99)).build());
        assertThatThrownBy(() -> issue("ada", "hotel", null)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.issue("ada", null, null, new HandoffDtos.Issue("hotel", null, false)))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void unknownCodeIsRefused() {
        assertThatThrownBy(() -> service.exchange(new HandoffDtos.Exchange("not-a-code", "admin")))
                .isInstanceOf(UnauthorizedException.class).hasMessageContaining("expired");
    }

    @Test
    void nextIsOnlyEverARelativePathOfTheTargetApp() {
        assertThat(HandoffService.safeNext("/dashboard/businesses?q=a")).isEqualTo("/dashboard/businesses?q=a");
        assertThat(HandoffService.safeNext("//evil.example/x")).isNull();
        assertThat(HandoffService.safeNext("/\\evil.example")).isNull();
        assertThat(HandoffService.safeNext("https://evil.example")).isNull();
        assertThat(HandoffService.safeNext("javascript:alert(1)")).isNull();
        assertThat(HandoffService.safeNext("/dashboard/../x")).isNull();
        assertThat(HandoffService.safeNext("/a b")).isNull();
        assertThat(HandoffService.safeNext("/" + "a".repeat(600))).isNull();
        assertThat(HandoffService.origin("javascript:alert(1)")).isEmpty();
        assertThat(HandoffService.origin("https://Admin.Example:8443/x?y")).isEqualTo("https://admin.example:8443");
    }
}

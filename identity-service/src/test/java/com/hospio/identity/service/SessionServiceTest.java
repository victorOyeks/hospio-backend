package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.exception.UnauthorizedException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.AccessChangedEvent;
import com.hospio.common.security.JwtUtil;
import com.hospio.identity.client.TenantClient;
import com.hospio.identity.dto.AuthResponse;
import com.hospio.identity.dto.ProfileDtos;
import com.hospio.identity.dto.RefreshTokenRequest;
import com.hospio.identity.entity.RefreshToken;
import com.hospio.identity.entity.SharedDevice;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RefreshTokenRepository;
import com.hospio.identity.repository.RoleGrantRepository;
import com.hospio.identity.repository.SharedDeviceRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Sessions (docs/profile.md): the list marks this one and names PIN devices, ending one or all others revokes and marks their
 * tokens and makes the person's older access tokens stale, nobody reaches someone else's session, and a refresh of an ended
 * session is refused.
 */
class SessionServiceTest {

    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final SharedDeviceRepository deviceRepo = mock(SharedDeviceRepository.class);
    private final RoleGrantRepository grants = mock(RoleGrantRepository.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final TokenIssuer tokenIssuer = mock(TokenIssuer.class);
    private final SharedDeviceService devices = new SharedDeviceService(deviceRepo, users, grants, refreshTokens, mock(TenantClient.class), events);
    private final JwtUtil jwt = new JwtUtil();
    private final SessionService sessions;

    private final List<RefreshToken> tokens = new ArrayList<>();
    private final UserAccount ada = UserAccount.builder().id("ada").businessId("b1").fullName("Ada Obi").email("ada@x.ng").build();

    SessionServiceTest() {
        ReflectionTestUtils.setField(jwt, "secret", "test-secret-test-secret-test-secret-test-secret");
        ReflectionTestUtils.setField(jwt, "expirationMs", 60_000L);
        ReflectionTestUtils.invokeMethod(jwt, "buildParser");
        sessions = new SessionService(refreshTokens, users, devices, tokenIssuer, events, jwt);
    }

    private RefreshToken token(String user, String session, String device, String agent, int minutesAgo) {
        RefreshToken t = RefreshToken.builder().id(session + "-" + minutesAgo).userId(user).sessionId(session).deviceId(device)
                .userAgent(agent).tokenHash("h-" + session + minutesAgo).expiresAt(Instant.now().plusSeconds(86400))
                .signedInAt(Instant.now().minusSeconds(86400)).createdAt(Instant.now().minusSeconds(minutesAgo * 60L)).build();
        tokens.add(t);
        return t;
    }

    private void callingAs(String sid) {
        String access = jwt.generateSessionToken("ada", "ada@x.ng", "Ada Obi", "b1", List.of("h1"), Map.of(Role.MANAGER, AccessLevel.FULL),
                JwtUtil.SCOPE_HOTEL, sid);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + access);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @BeforeEach
    void setUp() {
        when(users.lockById("ada")).thenReturn(Optional.of(ada));
        when(refreshTokens.findLive(anyString(), any())).thenAnswer(inv -> tokens.stream()
                .filter(t -> t.getUserId().equals(inv.getArgument(0)) && t.isUsable()).toList());
        when(refreshTokens.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SharedDevice bar = SharedDevice.builder().id("d1").businessId("b1").hotelId("h1").name("Bar tablet").roles("BAR").build();
        when(deviceRepo.findById("d1")).thenReturn(Optional.of(bar));
        token("ada", "s-mac", null, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/131.0 Safari/537.36", 30);
        token("ada", "s-phone", null, "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Version/17.0 Mobile Safari/604.1", 5);
        token("ada", "s-bar", "d1", null, 60);
        token("ola", "s-ola", null, "curl/8", 1);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void theListIsMineWithThisSessionFirstAndPinDevicesNamed() {
        RefreshToken rotated = token("ada", "s-mac", null, "Mozilla/5.0 (Macintosh; Mac OS X) Chrome/131.0", 90);
        rotated.setRevokedAt(Instant.now());
        callingAs("s-mac");
        List<ProfileDtos.Session> list = sessions.list("ada");
        assertThat(list).extracting(ProfileDtos.Session::id).containsExactly("s-mac", "s-phone", "s-bar");
        assertThat(list.get(0).current()).isTrue();
        assertThat(list.get(0).device()).isEqualTo("Chrome on macOS");
        assertThat(list.get(1).device()).isEqualTo("Safari on iPhone");
        assertThat(list.get(2).kind()).isEqualTo("PIN");
        assertThat(list.get(2).deviceName()).isEqualTo("Bar tablet");
        assertThat(list).allMatch(s -> s.location() == null);
    }

    @Test
    void endingOneRevokesItAndMakesOldAccessTokensStale() {
        callingAs("s-mac");
        sessions.endOne("ada", "s-phone");
        RefreshToken phone = tokens.stream().filter(t -> t.getSessionId().equals("s-phone")).findFirst().orElseThrow();
        assertThat(phone.getRevokedAt()).isNotNull();
        assertThat(phone.getEndedAt()).isNotNull();
        assertThat(tokens.stream().filter(t -> !t.getSessionId().equals("s-phone"))).allMatch(t -> t.getRevokedAt() == null);
        ArgumentCaptor<AccessChangedEvent> sent = ArgumentCaptor.forClass(AccessChangedEvent.class);
        verify(events).publish(eq(KafkaTopics.ACCESS_CHANGED), sent.capture());
        assertThat(sent.getValue().getUserId()).isEqualTo("ada");
        assertThat(sent.getValue().getChange()).isEqualTo("SESSIONS_ENDED");
        assertThat(sent.getValue().summary()).isEqualTo("Ada Obi: signed out elsewhere (signed out one device)");
    }

    @Test
    void thisSessionAndOtherPeoplesSessionsCantBeEndedHere() {
        callingAs("s-mac");
        assertThatThrownBy(() -> sessions.endOne("ada", "s-mac")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> sessions.endOne("ada", "s-ola")).isInstanceOf(ResourceNotFoundException.class);
        assertThat(tokens.stream().filter(t -> t.getUserId().equals("ola"))).allMatch(t -> t.getRevokedAt() == null);
        verify(events, never()).publish(anyString(), any());
    }

    @Test
    void signingOutOthersKeepsThisSessionAndTheOtherPersons() {
        callingAs("s-mac");
        assertThat(sessions.endOthers("ada", "s-mac", "signed out other devices")).isEqualTo(2);
        assertThat(tokens).filteredOn(t -> t.getUserId().equals("ada") && !t.getSessionId().equals("s-mac"))
                .allMatch(t -> t.getEndedAt() != null && t.getRevokedAt() != null);
        assertThat(tokens).filteredOn(t -> t.getSessionId().equals("s-mac") || t.getUserId().equals("ola"))
                .allMatch(t -> t.getRevokedAt() == null);
    }

    @Test
    void reissueRotatesThisSessionAndKeepsItsId() {
        when(tokenIssuer.continueSession(eq(ada), any(), any())).thenReturn(new AuthResponse("a2", "r2", 3600, List.of(), false, false));
        assertThat(sessions.reissue("ada", "s-mac").accessToken()).isEqualTo("a2");
        RefreshToken mac = tokens.stream().filter(t -> t.getSessionId().equals("s-mac")).findFirst().orElseThrow();
        assertThat(mac.getRevokedAt()).isNotNull();
        assertThat(mac.getEndedAt()).isNull();
        verify(tokenIssuer).continueSession(ada, mac, null);
    }

    @Test
    void aRefreshOfAnEndedSessionIsRefused() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        AuthService auth = new AuthService(users, refreshTokens, encoder, tokenIssuer, events, grants, devices, mock(TwoStepService.class), sessions);
        RefreshToken t = RefreshToken.builder().id("x").userId("ada").sessionId("s-old").tokenHash(Hashing.sha256("raw"))
                .expiresAt(Instant.now().plusSeconds(600)).build();
        when(refreshTokens.findByTokenHash(Hashing.sha256("raw"))).thenReturn(Optional.of(t));
        when(refreshTokens.sessionEnded("s-old")).thenReturn(true);
        assertThatThrownBy(() -> auth.refresh(new RefreshTokenRequest("raw"))).isInstanceOf(UnauthorizedException.class);
        verify(tokenIssuer, never()).continueSession(any(), any(), any());
    }

    @Test
    void browsersAreNamedFromTheUserAgent() {
        assertThat(ClientInfo.describe(null)).isEqualTo("Unknown browser");
        assertThat(ClientInfo.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36 Edg/131.0"))
                .isEqualTo("Edge on Windows");
        assertThat(ClientInfo.describe("Mozilla/5.0 (Linux; Android 14) Gecko/20100101 Firefox/133.0")).isEqualTo("Firefox on Android");
        assertThat(ClientInfo.describe("node")).isEqualTo("App server");
    }
}

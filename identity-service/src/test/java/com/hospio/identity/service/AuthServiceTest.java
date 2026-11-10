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
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.UnauthorizedException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.kafka.events.SharedDeviceEvent;
import com.hospio.identity.client.TenantClient;
import com.hospio.identity.dto.AuthResponse;
import com.hospio.identity.dto.LoginRequest;
import com.hospio.identity.dto.PinLoginRequest;
import com.hospio.identity.dto.RefreshTokenRequest;
import com.hospio.identity.dto.SetPinRequest;
import com.hospio.identity.entity.RefreshToken;
import com.hospio.identity.entity.RoleGrant;
import com.hospio.identity.entity.SharedDevice;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RefreshTokenRepository;
import com.hospio.identity.repository.RoleGrantRepository;
import com.hospio.identity.repository.SharedDeviceRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Sign-in limits and PIN unlock on a shared device (docs/shared-devices.md): only on a device of the person's hotel and for a
 * role it is for; wrong PINs count against the person and the fifth locks the PIN and tells the managers; paused and suspended
 * people can't unlock; a device session's refresh ends with the device; PIN rules on set.
 */
class AuthServiceTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final TokenIssuer tokenIssuer = mock(TokenIssuer.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final RoleGrantRepository grants = mock(RoleGrantRepository.class);
    private final SharedDeviceRepository deviceRepo = mock(SharedDeviceRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final SharedDeviceService devices = new SharedDeviceService(deviceRepo, users, grants, refreshTokens, mock(TenantClient.class), events);
    private final AuthService service = new AuthService(users, refreshTokens, encoder, tokenIssuer, events, grants, devices);

    private final List<RoleGrant> graceGrants = new ArrayList<>();
    private SharedDevice bar;
    private UserAccount grace;

    AuthServiceTest() {
        ReflectionTestUtils.setField(service, "pinMaxAttempts", 5);
        ReflectionTestUtils.setField(service, "pinLockMinutes", 15L);
        ReflectionTestUtils.setField(service, "signInMaxAttempts", 5);
        ReflectionTestUtils.setField(service, "signInLockMinutes", 15L);
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(deviceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tokenIssuer.issue(any())).thenReturn(new AuthResponse("a", "r", 3600, List.of("FRONT_OFFICE"), false, false));
        when(tokenIssuer.issueOnDevice(any(), anyString(), anyString())).thenReturn(new AuthResponse("pin", "r", 3600, List.of("BAR"), false, false));
    }

    @BeforeEach
    void setUp() {
        bar = SharedDevice.builder().businessId("b1").hotelId("h1").name("Bar tablet").roles("BAR").tokenHash(Hashing.sha256("dev-token")).build();
        bar.setId("d1");
        when(deviceRepo.findByTokenHash(Hashing.sha256("dev-token"))).thenReturn(Optional.of(bar));
        when(deviceRepo.findById("d1")).thenReturn(Optional.of(bar));
        grace = UserAccount.builder().businessId("b1").fullName("Grace Bello").email("grace@x.ng").phone("+2348010000012")
                .passwordHash(encoder.encode("Hospio123!")).pinHash(encoder.encode("2580")).build();
        grace.setId("grace");
        when(users.findById("grace")).thenReturn(Optional.of(grace));
        graceGrants.add(grant("grace", Role.BAR, "h1"));
        when(grants.findByUserId("grace")).thenAnswer(i -> graceGrants);
    }

    private static RoleGrant grant(String user, Role role, String hotel) {
        RoleGrant g = RoleGrant.builder().userId(user).businessId("b1").hotelId(hotel).role(role).level(AccessLevel.FULL).build();
        g.setId(user + "-" + role + "-" + hotel);
        return g;
    }

    private AuthResponse unlock(String pin) {
        return service.signInWithPin("dev-token", new PinLoginRequest("grace", pin));
    }

    @Test
    void rightPinOnTheDeviceGivesASessionAtItsHotelAndRecordsTheDevice() {
        grace.setFailedPinAttempts(3);
        AuthResponse response = unlock("2580");

        assertThat(response.accessToken()).isEqualTo("pin");
        verify(tokenIssuer).issueOnDevice(grace, "h1", "d1");
        assertThat(grace.getFailedPinAttempts()).isZero();
        assertThat(bar.getLastUserName()).isEqualTo("Grace Bello");
        assertThat(bar.getLastUsedAt()).isNotNull();
        verify(events).publish(eq(KafkaTopics.USER_SIGNED_IN), any());
    }

    @Test
    void wrongPinsCountDownThenTheFifthLocksThePinAndTellsTheManagers() {
        assertThatThrownBy(() -> unlock("9999")).isInstanceOf(UnauthorizedException.class)
                .hasMessage("Wrong PIN. 4 tries left before your PIN is locked for 15 minutes");
        grace.setFailedPinAttempts(4);
        assertThatThrownBy(() -> unlock("9999")).isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Your PIN is now locked for 15 minutes");
        assertThat(grace.isLocked()).isTrue();
        assertThat(grace.getFailedPinAttempts()).isZero();

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.atLeastOnce()).publish(eq(KafkaTopics.NOTICE), (NoticeEvent) sent.capture());
        NoticeEvent notice = (NoticeEvent) sent.getValue();
        assertThat(notice.getRoles()).containsExactly("MANAGER");
        assertThat(notice.getHotelId()).isEqualTo("h1");
        assertThat(notice.getTitle()).isEqualTo("Grace Bello's PIN is locked");

        // Even the right PIN is refused while locked, with the minutes left.
        assertThatThrownBy(() -> unlock("2580")).isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Your PIN is locked for 15 more minutes");
        // The device itself is not locked: it still answers.
        assertThat(devices.requireDevice("dev-token")).isSameAs(bar);
    }

    @Test
    void wrongPinsAreLoggedWithTheDevice() {
        assertThatThrownBy(() -> unlock("1111")).isInstanceOf(UnauthorizedException.class);
        ArgumentCaptor<SharedDeviceEvent> sent = ArgumentCaptor.forClass(SharedDeviceEvent.class);
        verify(events).publish(eq(KafkaTopics.SHARED_DEVICE), sent.capture());
        assertThat(sent.getValue().getAction()).isEqualTo("PIN_FAILED");
        assertThat(sent.getValue().getHotelId()).isEqualTo("h1");
        assertThat(sent.getValue().summary()).isEqualTo("Wrong PIN for Grace Bello on Bar tablet (1 of 5)");
    }

    @Test
    void aPersonOfAnotherHotelOrBusinessCannotUnlock() {
        graceGrants.clear();
        graceGrants.add(grant("grace", Role.BAR, "h2"));
        assertThatThrownBy(() -> unlock("2580")).isInstanceOf(ForbiddenException.class).hasMessageContaining("doesn't work on Bar tablet");

        UserAccount other = UserAccount.builder().businessId("b2").fullName("Ola").email("ola@y.ng").pinHash(encoder.encode("2580")).build();
        other.setId("ola");
        when(users.findById("ola")).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.signInWithPin("dev-token", new PinLoginRequest("ola", "2580")))
                .isInstanceOf(UnauthorizedException.class).hasMessageContaining("don't know that person");
    }

    @Test
    void aRoleTheDeviceIsNotForCannotUnlock() {
        graceGrants.clear();
        graceGrants.add(grant("grace", Role.KITCHEN, "h1"));
        assertThatThrownBy(() -> unlock("2580")).isInstanceOf(ForbiddenException.class);
        graceGrants.clear();
        RoleGrant paused = grant("grace", Role.BAR, "h1");
        paused.setPaused(true);
        graceGrants.add(paused);
        assertThatThrownBy(() -> unlock("2580")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void pausedOrSuspendedPeopleCannotUnlock() {
        grace.setStatus(UserAccount.Status.SUSPENDED);
        assertThatThrownBy(() -> unlock("2580")).isInstanceOf(ForbiddenException.class).hasMessageContaining("suspended");
        grace.setStatus(UserAccount.Status.PAUSED);
        grace.setPausedUntil(Instant.now().plusSeconds(86_400));
        assertThatThrownBy(() -> unlock("2580")).isInstanceOf(ForbiddenException.class).hasMessageContaining("paused until");
        verify(tokenIssuer, never()).issueOnDevice(any(), anyString(), anyString());
    }

    @Test
    void ownersHaveNoPin() {
        graceGrants.add(grant("grace", Role.OWNER, null));
        assertThatThrownBy(() -> unlock("2580")).isInstanceOf(ForbiddenException.class).hasMessageContaining("Owners");
        assertThatThrownBy(() -> service.setPin("grace", new SetPinRequest("2470", "Hospio123!"))).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aRemovedOrUnknownDeviceIsRefused() {
        bar.setRevokedAt(Instant.now());
        assertThatThrownBy(() -> unlock("2580")).isInstanceOf(UnauthorizedException.class).hasMessageContaining("removed");
        assertThatThrownBy(() -> service.signInWithPin("nope", new PinLoginRequest("grace", "2580")))
                .isInstanceOf(UnauthorizedException.class).hasMessageContaining("not set up");
        assertThatThrownBy(() -> service.signInWithPin(null, new PinLoginRequest("grace", "2580")))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aDeviceSessionRefreshesOnlyWhileTheDeviceAndTheRoleLast() {
        RefreshToken token = RefreshToken.builder().userId("grace").tokenHash(Hashing.sha256("rt")).deviceId("d1")
                .expiresAt(Instant.now().plusSeconds(3600)).build();
        when(refreshTokens.findByTokenHash(Hashing.sha256("rt"))).thenReturn(Optional.of(token));
        assertThat(service.refresh(new RefreshTokenRequest("rt")).accessToken()).isEqualTo("pin");
        verify(tokenIssuer).issueOnDevice(grace, "h1", "d1");

        token.setRevokedAt(null);
        graceGrants.clear();
        assertThatThrownBy(() -> service.refresh(new RefreshTokenRequest("rt"))).isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("no longer have a role");

        token.setRevokedAt(null);
        graceGrants.add(grant("grace", Role.BAR, "h1"));
        bar.setRevokedAt(Instant.now());
        assertThatThrownBy(() -> service.refresh(new RefreshTokenRequest("rt"))).isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("no longer a shared device");
    }

    @Test
    void lockingEndsOnlyThatSession() {
        RefreshToken token = RefreshToken.builder().userId("grace").tokenHash(Hashing.sha256("rt")).deviceId("d1")
                .expiresAt(Instant.now().plusSeconds(3600)).build();
        when(refreshTokens.findByTokenHash(Hashing.sha256("rt"))).thenReturn(Optional.of(token));
        service.lockDevice("dev-token", "rt", "idle");
        assertThat(token.getRevokedAt()).isNotNull();
        verify(refreshTokens, never()).findByUserIdAndRevokedAtIsNull(anyString());
        ArgumentCaptor<SharedDeviceEvent> sent = ArgumentCaptor.forClass(SharedDeviceEvent.class);
        verify(events).publish(eq(KafkaTopics.SHARED_DEVICE), sent.capture());
        assertThat(sent.getValue().summary()).isEqualTo("Grace Bello locked Bar tablet (idle 5 min)");
    }

    @Test
    void setPinNeedsThePasswordAndRefusesTrivialOrRepeatedPins() {
        assertThatThrownBy(() -> service.setPin("grace", new SetPinRequest("2470", "wrong"))).isInstanceOf(BadRequestException.class)
                .hasMessage("Your password is wrong");
        assertThatThrownBy(() -> service.setPin("grace", new SetPinRequest("1234", "Hospio123!"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.setPin("grace", new SetPinRequest("0012", "Hospio123!"))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("phone");
        assertThatThrownBy(() -> service.setPin("grace", new SetPinRequest("2580", "Hospio123!"))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("current PIN");

        grace.setFailedPinAttempts(2);
        grace.setLockedUntil(Instant.now().plusSeconds(600));
        service.setPin("grace", new SetPinRequest("2470", "Hospio123!"));
        assertThat(encoder.matches("2470", grace.getPinHash())).isTrue();
        assertThat(grace.getFailedPinAttempts()).isZero();
        assertThat(grace.getLockedUntil()).isNull();
    }

    @Test
    void expiredTemporaryPasswordIsRefused() {
        UserAccount tunde = UserAccount.builder().fullName("Tunde").email("tunde@x.ng")
                .passwordHash(encoder.encode("Temp123456")).mustChangePassword(true)
                .temporaryPasswordExpiresAt(Instant.now().minusSeconds(60)).build();
        when(users.findByEmailIgnoreCase("tunde@x.ng")).thenReturn(Optional.of(tunde));

        assertThatThrownBy(() -> service.signIn(new LoginRequest("tunde@x.ng", "Temp123456")))
                .isInstanceOf(UnauthorizedException.class).hasMessageContaining("temporary password has expired");
    }
}

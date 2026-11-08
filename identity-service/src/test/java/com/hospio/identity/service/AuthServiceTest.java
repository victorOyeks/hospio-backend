package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.exception.UnauthorizedException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.identity.dto.AuthResponse;
import com.hospio.identity.dto.LoginRequest;
import com.hospio.identity.dto.PinLoginRequest;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RefreshTokenRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/** Wrong PINs lock the account after the fifth try; an expired temporary password is refused with a clear message. */
class AuthServiceTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final TokenIssuer tokenIssuer = mock(TokenIssuer.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final AuthService service = new AuthService(users, refreshTokens, encoder, tokenIssuer, events);

    AuthServiceTest() {
        ReflectionTestUtils.setField(service, "pinMaxAttempts", 5);
        ReflectionTestUtils.setField(service, "pinLockMinutes", 15L);
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tokenIssuer.issue(any())).thenReturn(new AuthResponse("a", "r", 3600, List.of("FRONT_OFFICE"), false, false));
    }

    @Test
    void fifthWrongPinLocksTheAccount() {
        UserAccount grace = UserAccount.builder().fullName("Grace").email("grace@x.ng").phone("+2348010000012")
                .pinHash(encoder.encode("1234")).failedPinAttempts(4).build();
        when(users.findByPhone("+2348010000012")).thenReturn(Optional.of(grace));

        assertThatThrownBy(() -> service.signInWithPin(new PinLoginRequest("+2348010000012", "9999")))
                .isInstanceOf(UnauthorizedException.class).hasMessage("Wrong PIN");
        assertThat(grace.isLocked()).isTrue();
        assertThat(grace.getFailedPinAttempts()).isZero();

        assertThatThrownBy(() -> service.signInWithPin(new PinLoginRequest("+2348010000012", "1234")))
                .isInstanceOf(UnauthorizedException.class).hasMessageContaining("Too many wrong PINs");
    }

    @Test
    void rightPinResetsTheCounterAndIssuesTokens() {
        UserAccount grace = UserAccount.builder().fullName("Grace").email("grace@x.ng").phone("+2348010000012")
                .pinHash(encoder.encode("1234")).failedPinAttempts(3).build();
        when(users.findByPhone("+2348010000012")).thenReturn(Optional.of(grace));

        AuthResponse response = service.signInWithPin(new PinLoginRequest("+2348010000012", "1234"));

        assertThat(response.accessToken()).isEqualTo("a");
        assertThat(grace.getFailedPinAttempts()).isZero();
        assertThat(grace.getLastSignInAt()).isNotNull();
        verify(events).publish(any(), any());
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

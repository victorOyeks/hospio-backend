package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.UnauthorizedException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.identity.client.TenantClient;
import com.hospio.identity.dto.AuthResponse;
import com.hospio.identity.dto.LoginRequest;
import com.hospio.identity.dto.ProfileDtos;
import com.hospio.identity.entity.RecoveryCode;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RecoveryCodeRepository;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 2-step with an authenticator app (docs/profile.md): setup and the confirming code, the sign-in code step, wrong codes
 * counting toward the sign-in lock (a right password doesn't reset it), recovery codes working once, new codes, turning off.
 */
class TwoStepServiceTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final RecoveryCodeRepository codes = mock(RecoveryCodeRepository.class);
    private final RoleGrantRepository grants = mock(RoleGrantRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final TokenIssuer tokenIssuer = mock(TokenIssuer.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final TwoStepSecrets secrets = new TwoStepSecrets("", "test-jwt-secret");
    private final TwoStepService twoStep = new TwoStepService(users, codes, grants, secrets, encoder);
    private final SharedDeviceService devices = new SharedDeviceService(mock(SharedDeviceRepository.class), users, grants, refreshTokens,
            mock(TenantClient.class), mock(EventPublisher.class));
    private final AuthService auth = new AuthService(users, refreshTokens, encoder, tokenIssuer, mock(EventPublisher.class), grants, devices,
            twoStep, mock(SessionService.class));

    private final List<RecoveryCode> stored = new ArrayList<>();
    private UserAccount ada;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(auth, "signInMaxAttempts", 5);
        ReflectionTestUtils.setField(auth, "signInLockMinutes", 15L);
        ada = UserAccount.builder().id("ada").businessId("b1").fullName("Ada Obi").email("ada@x.ng")
                .passwordHash(encoder.encode("Secret123!")).build();
        when(users.findById("ada")).thenReturn(Optional.of(ada));
        when(users.findByEmailIgnoreCase("ada@x.ng")).thenReturn(Optional.of(ada));
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(users.findByTwoStepChallengeHash(anyString())).thenAnswer(inv ->
                Optional.ofNullable(inv.getArgument(0).equals(ada.getTwoStepChallengeHash()) ? ada : null));
        when(codes.save(any())).thenAnswer(inv -> {
            RecoveryCode c = inv.getArgument(0);
            if (!stored.contains(c)) {
                stored.add(c);
            }
            return c;
        });
        doAnswer(inv -> stored.removeIf(c -> c.getUserId().equals(inv.getArgument(0)))).when(codes).deleteByUser(anyString());
        when(codes.findByUserIdAndCodeHash(anyString(), anyString())).thenAnswer(inv -> stored.stream()
                .filter(c -> c.getUserId().equals(inv.getArgument(0)) && c.getCodeHash().equals(inv.getArgument(1))).findFirst());
        when(codes.countByUserIdAndUsedAtIsNull(anyString())).thenAnswer(inv -> stored.stream().filter(c -> c.getUsedAt() == null).count());
        when(tokenIssuer.issue(any())).thenReturn(new AuthResponse("access", "refresh", 3600, List.of("MANAGER"), false, false));
    }

    private byte[] secretOf(String sealed) {
        return secrets.open(sealed);
    }

    /** The app's code for a step that hasn't been used yet (the next one when the current one was). */
    private String freshCode() {
        long now = Instant.now().getEpochSecond();
        long step = Totp.step(now);
        if (ada.getTwoStepLastStep() != null && ada.getTwoStepLastStep() >= step) {
            return Totp.codeAt(secretOf(ada.getTwoStepSecret()), (ada.getTwoStepLastStep() + 1) * Totp.STEP_SECONDS);
        }
        return Totp.codeAt(secretOf(ada.getTwoStepSecret()), now);
    }

    private List<String> turnOn() {
        ProfileDtos.TwoStepSetup setup = twoStep.setup("ada");
        assertThat(setup.otpauthUri()).startsWith("otpauth://totp/Hospio360%3Aada%40x.ng?secret=").contains("issuer=Hospio360");
        assertThat(setup.qrSvg()).startsWith("<svg");
        byte[] secret = Totp.fromBase32(setup.secret());
        assertThat(ada.isTwoStepEnabled()).isFalse();
        assertThat(ada.getTwoStepPendingSecret()).startsWith("v1:").doesNotContain(Totp.base32(secret));
        return twoStep.enable("ada", Totp.codeAt(secret, Instant.now().getEpochSecond())).recoveryCodes();
    }

    @Test
    void setupNeedsTheRightFirstCodeThenGivesTenRecoveryCodes() {
        twoStep.setup("ada");
        assertThatThrownBy(() -> twoStep.enable("ada", "000000")).isInstanceOf(BadRequestException.class).hasMessageContaining("wrong");
        assertThat(ada.getFailedSignInAttempts()).isZero();
        List<String> recovery = turnOn();
        assertThat(recovery).hasSize(10).allMatch(c -> c.matches("[a-z2-9]{5}-[a-z2-9]{5}")).doesNotHaveDuplicates();
        assertThat(ada.isTwoStepEnabled()).isTrue();
        assertThat(ada.getTwoStepPendingSecret()).isNull();
        assertThat(stored).hasSize(10).noneMatch(c -> recovery.contains(c.getCodeHash()));
        assertThat(twoStep.status("ada").recoveryCodesLeft()).isEqualTo(10);
        assertThatThrownBy(() -> twoStep.setup("ada")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void signInAsksForTheCodeAfterThePasswordAndAcceptsItOnce() {
        turnOn();
        AuthResponse first = auth.signIn(new LoginRequest("ada@x.ng", "Secret123!"));
        assertThat(first.accessToken()).isNull();
        assertThat(first.refreshToken()).isNull();
        assertThat(first.twoStepRequired()).isTrue();
        assertThat(first.twoStepToken()).isNotBlank();

        // The code the app shows was already used to turn 2-step on: refused (codes work once).
        String used = Totp.codeAt(secretOf(ada.getTwoStepSecret()), ada.getTwoStepLastStep() * Totp.STEP_SECONDS);
        assertThatThrownBy(() -> auth.signInWithCode(first.twoStepToken(), used)).isInstanceOf(UnauthorizedException.class);
        assertThat(ada.getFailedSignInAttempts()).isEqualTo(1);

        AuthResponse done = auth.signInWithCode(first.twoStepToken(), freshCode());
        assertThat(done.accessToken()).isEqualTo("access");
        assertThat(ada.getFailedSignInAttempts()).isZero();
        // The token is used up.
        assertThatThrownBy(() -> auth.signInWithCode(first.twoStepToken(), freshCode())).isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void wrongCodesCountWithWrongPasswordsAndARightPasswordDoesNotResetThem() {
        turnOn();
        for (int i = 1; i <= 4; i++) {
            String token = auth.signIn(new LoginRequest("ada@x.ng", "Secret123!")).twoStepToken();
            assertThatThrownBy(() -> auth.signInWithCode(token, "000000")).isInstanceOf(UnauthorizedException.class);
            assertThat(ada.getFailedSignInAttempts()).isEqualTo(i);
        }
        String token = auth.signIn(new LoginRequest("ada@x.ng", "Secret123!")).twoStepToken();
        assertThatThrownBy(() -> auth.signInWithCode(token, "000000")).isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("locked for 15 minutes");
        assertThat(ada.getSignInLockedUntil()).isAfter(Instant.now());
        // Locked: even the right password is refused now.
        assertThatThrownBy(() -> auth.signIn(new LoginRequest("ada@x.ng", "Secret123!"))).isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("locked");
    }

    @Test
    void aRecoveryCodeSignsInOnceInAnyCase() {
        List<String> recovery = turnOn();
        String token = auth.signIn(new LoginRequest("ada@x.ng", "Secret123!")).twoStepToken();
        assertThat(auth.signInWithCode(token, recovery.get(0).toUpperCase().replace("-", " ")).accessToken()).isEqualTo("access");
        assertThat(twoStep.status("ada").recoveryCodesLeft()).isEqualTo(9);
        String again = auth.signIn(new LoginRequest("ada@x.ng", "Secret123!")).twoStepToken();
        assertThatThrownBy(() -> auth.signInWithCode(again, recovery.get(0))).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void newCodesReplaceTheOldOnesAndNeedACurrentCode() {
        List<String> old = turnOn();
        assertThatThrownBy(() -> twoStep.regenerate("ada", "000000")).isInstanceOf(BadRequestException.class);
        assertThat(ada.getFailedSignInAttempts()).isEqualTo(1);
        List<String> fresh = twoStep.regenerate("ada", freshCode()).recoveryCodes();
        assertThat(fresh).hasSize(10).doesNotContainAnyElementsOf(old);
        assertThat(ada.getFailedSignInAttempts()).isZero();
        String token = auth.signIn(new LoginRequest("ada@x.ng", "Secret123!")).twoStepToken();
        assertThatThrownBy(() -> auth.signInWithCode(token, old.get(0))).isInstanceOf(UnauthorizedException.class);
        assertThat(auth.signInWithCode(token, fresh.get(3)).accessToken()).isEqualTo("access");
    }

    @Test
    void turningOffNeedsThePasswordAndACode() {
        List<String> recovery = turnOn();
        assertThatThrownBy(() -> twoStep.disable("ada", "wrong", freshCode())).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("password");
        assertThatThrownBy(() -> twoStep.disable("ada", "Secret123!", "000000")).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("code");
        assertThat(ada.getFailedSignInAttempts()).isEqualTo(2);
        assertThat(twoStep.disable("ada", "Secret123!", recovery.get(1)).enabled()).isFalse();
        assertThat(ada.getTwoStepSecret()).isNull();
        assertThat(stored).isEmpty();
        // Off: sign-in gives tokens straight away again.
        assertThat(auth.signIn(new LoginRequest("ada@x.ng", "Secret123!")).accessToken()).isEqualTo("access");
    }

    @Test
    void theSecretIsEncryptedAndOnlyOpensWithTheSameKey() {
        byte[] secret = Totp.newSecret();
        String sealed = secrets.seal(secret);
        assertThat(sealed).isNotEqualTo(secrets.seal(secret));
        assertThat(secrets.open(sealed)).isEqualTo(secret);
        assertThatThrownBy(() -> new TwoStepSecrets("other-key", "x").open(sealed)).isInstanceOf(IllegalStateException.class);
    }
}

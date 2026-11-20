package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.exception.BadRequestException;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.UserAccountRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The dev/staging fixed one-time code (hospio.otp.fixed-code): the start-up guard that keeps it off production, and the
 * phone code being that code, still sent, and accepted. The 2-step side is in TwoStepServiceTest.
 */
class FixedOtpTest {

    private static FixedOtp build(String code, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new FixedOtp(code, env);
    }

    @Test
    void emptyByDefaultIsOffOnEveryProfile() {
        assertThat(build("", "prod").on()).isFalse();
        assertThat(build(null).on()).isFalse();
        assertThat(build("  ", "staging").matches("")).isFalse();
        assertThat(FixedOtp.OFF.on()).isFalse();
        assertThat(FixedOtp.OFF.matches("159753")).isFalse();
    }

    @Test
    void onForDevAndStaging() {
        FixedOtp staging = build("159753", "staging");
        assertThat(staging.on()).isTrue();
        assertThat(staging.code()).isEqualTo("159753");
        assertThat(staging.matches("159 753")).isTrue();
        assertThat(staging.matches("123456")).isFalse();
        assertThat(staging.matches(null)).isFalse();
        assertThat(build("159753", "dev").on()).isTrue();
    }

    @Test
    void startUpFailsWhenSetOnAProductionProfile() {
        for (String prod : List.of("prod", "production", "live", "PROD")) {
            assertThatThrownBy(() -> build("159753", prod)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("production profile");
        }
        // A production profile wins even when staging is listed too.
        assertThatThrownBy(() -> build("159753", "staging", "prod")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startUpFailsWhenSetWithoutADevOrStagingProfile() {
        assertThatThrownBy(() -> build("159753")).isInstanceOf(IllegalStateException.class).hasMessageContaining("none of the profiles");
        assertThatThrownBy(() -> build("159753", "default")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mustBeSixDigits() {
        assertThatThrownBy(() -> build("1234", "staging")).isInstanceOf(IllegalStateException.class).hasMessageContaining("6 digits");
        assertThatThrownBy(() -> build("abcdef", "dev")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void thePhoneCodeIsTheFixedCodeIsStillSentAndIsAccepted() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        DeliveryService deliveries = mock(DeliveryService.class);
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
        when(deliveries.send(any(Delivery.class))).thenReturn(List.of());
        UserService userService = mock(UserService.class);
        PhoneVerificationService service = new PhoneVerificationService(users, userService,
                new DeliveryComposer("https://web.example", "https://admin.example"), deliveries);
        ReflectionTestUtils.setField(service, "codeMinutes", 10L);
        ReflectionTestUtils.setField(service, "cooldownSeconds", 60L);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        service.setFixedOtp(build("159753", "staging"));
        UserAccount user = UserAccount.builder().email("ada@hospio-qa.test").phone("+2349035911994").build();
        user.setId("u-1");
        when(users.findById("u-1")).thenReturn(Optional.of(user));

        service.sendCode("u-1", false);

        ArgumentCaptor<Delivery> sent = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveries).send(sent.capture());
        assertThat(sent.getValue().channel()).isEqualTo(Delivery.Channel.SMS);
        assertThat(sent.getValue().text()).contains("159753");
        assertThat(user.getPhoneCodeHash()).isEqualTo(Hashing.sha256("159753"));

        assertThatThrownBy(() -> service.verify("u-1", "111111")).isInstanceOf(BadRequestException.class);
        service.verify("u-1", "159753");
        assertThat(user.isPhoneVerified()).isTrue();
    }

    @Test
    void withoutTheFixedCodePhoneCodesStayRandomAndItIsRefused() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        DeliveryService deliveries = mock(DeliveryService.class);
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
        when(deliveries.send(any(Delivery.class))).thenReturn(List.of());
        PhoneVerificationService service = new PhoneVerificationService(users, mock(UserService.class),
                new DeliveryComposer("https://web.example", "https://admin.example"), deliveries);
        ReflectionTestUtils.setField(service, "codeMinutes", 10L);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        UserAccount user = UserAccount.builder().email("ada@hospio-qa.test").phone("+2349035911994").build();
        user.setId("u-1");
        when(users.findById("u-1")).thenReturn(Optional.of(user));
        service.sendCode("u-1", false);
        if (!user.getPhoneCodeHash().equals(Hashing.sha256("159753"))) {
            assertThatThrownBy(() -> service.verify("u-1", "159753")).isInstanceOf(BadRequestException.class);
        }
        assertThat(user.isPhoneVerified()).isFalse();
    }
}

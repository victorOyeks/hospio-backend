package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.delivery.DeliveryService;
import com.hospio.identity.dto.PhoneCodeResponse;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.UserAccountRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** No new phone code within 60 s of the last one, and opening "Check your phone" again never sends one. */
class PhoneCodeCooldownTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final DeliveryService deliveries = mock(DeliveryService.class);
    private PhoneVerificationService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
        when(deliveries.send(any(com.hospio.common.delivery.Delivery.class))).thenReturn(List.of());
        service = new PhoneVerificationService(users, mock(UserService.class),
                new DeliveryComposer("https://web.example", "https://admin.example"), deliveries);
        ReflectionTestUtils.setField(service, "codeMinutes", 10L);
        ReflectionTestUtils.setField(service, "cooldownSeconds", 60L);
        user = UserAccount.builder().email("ada@hospio-qa.test").phone("+2349035911994").build();
        user.setId("u-1");
        when(users.findById("u-1")).thenReturn(Optional.of(user));
    }

    @Test
    void firstCodeIsSentAndStartsTheCooldown() {
        PhoneCodeResponse first = service.sendCode("u-1", false);

        assertThat(first.sent()).isTrue();
        assertThat(first.retryInSeconds()).isEqualTo(60);
        assertThat(user.getPhoneCodeSentAt()).isNotNull();
        verify(deliveries, times(1)).send(any(com.hospio.common.delivery.Delivery.class));
    }

    @Test
    void aSecondCodeWithinTheCooldownIsRefusedAndNothingIsDelivered() {
        PhoneCodeResponse first = service.sendCode("u-1", false);
        String hash = user.getPhoneCodeHash();

        PhoneCodeResponse again = service.sendCode("u-1", false);

        assertThat(again.sent()).isFalse();
        assertThat(again.expiresAt()).isEqualTo(first.expiresAt());
        assertThat(again.retryInSeconds()).isBetween(1L, 60L);
        assertThat(again.deliveries()).isEmpty();
        assertThat(user.getPhoneCodeHash()).isEqualTo(hash);
        verify(deliveries, times(1)).send(any(com.hospio.common.delivery.Delivery.class));
    }

    @Test
    void afterTheCooldownANewCodeIsSent() {
        service.sendCode("u-1", false);
        String hash = user.getPhoneCodeHash();
        user.setPhoneCodeSentAt(Instant.now().minus(61, ChronoUnit.SECONDS));

        PhoneCodeResponse again = service.sendCode("u-1", false);

        assertThat(again.sent()).isTrue();
        assertThat(user.getPhoneCodeHash()).isNotEqualTo(hash);
        verify(deliveries, times(2)).send(any(com.hospio.common.delivery.Delivery.class));
    }

    @Test
    void openingThePageWithAValidCodeSendsNothingEvenAfterTheCooldown() {
        PhoneCodeResponse first = service.sendCode("u-1", false);
        user.setPhoneCodeSentAt(Instant.now().minus(5, ChronoUnit.MINUTES));

        PhoneCodeResponse open = service.sendCode("u-1", false, true);

        assertThat(open.sent()).isFalse();
        assertThat(open.expiresAt()).isEqualTo(first.expiresAt());
        assertThat(open.retryInSeconds()).isZero();
        verify(deliveries, times(1)).send(any(com.hospio.common.delivery.Delivery.class));
    }

    @Test
    void openingThePageWithNoCodeSendsOne() {
        PhoneCodeResponse open = service.sendCode("u-1", false, true);

        assertThat(open.sent()).isTrue();
        verify(deliveries, times(1)).send(any(com.hospio.common.delivery.Delivery.class));
    }

    @Test
    void openingThePageWithAnExpiredCodeSendsANewOne() {
        service.sendCode("u-1", false);
        user.setPhoneCodeSentAt(Instant.now().minus(11, ChronoUnit.MINUTES));
        user.setPhoneCodeExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));

        PhoneCodeResponse open = service.sendCode("u-1", false, true);

        assertThat(open.sent()).isTrue();
        verify(deliveries, times(2)).send(any(com.hospio.common.delivery.Delivery.class));
    }

    @Test
    void lookingAtTheCodeSendsNothing() {
        PhoneCodeResponse none = service.current("u-1");
        assertThat(none.expiresAt()).isNull();
        assertThat(none.retryInSeconds()).isZero();
        assertThat(none.sent()).isFalse();

        PhoneCodeResponse first = service.sendCode("u-1", false);
        PhoneCodeResponse waiting = service.current("u-1");
        assertThat(waiting.expiresAt()).isEqualTo(first.expiresAt());
        assertThat(waiting.retryInSeconds()).isPositive();
        verify(deliveries, times(1)).send(any(com.hospio.common.delivery.Delivery.class));
    }

    @Test
    void signUpIssueRespectsTheCooldownToo() {
        user.setPhoneCodeSentAt(Instant.now().minus(10, ChronoUnit.SECONDS));

        PhoneCodeResponse issued = service.issue(user);

        assertThat(issued.sent()).isFalse();
        verify(deliveries, never()).send(any(com.hospio.common.delivery.Delivery.class));
    }
}

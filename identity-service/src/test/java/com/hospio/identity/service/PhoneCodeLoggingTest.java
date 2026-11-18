package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.UserAccountRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

/** A phone code is a credential: it goes to the phone and never into the log (18 Nov 2026 audit). */
class PhoneCodeLoggingTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(PhoneVerificationService.class);

    @BeforeEach
    void listen() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void stop() {
        logger.detachAppender(logs);
    }

    @Test
    void theCodeIsSentButNeverLogged() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
        DeliveryService deliveries = mock(DeliveryService.class);
        ArgumentCaptor<Delivery> sent = ArgumentCaptor.forClass(Delivery.class);
        when(deliveries.send(sent.capture())).thenReturn(List.of());
        PhoneVerificationService service = new PhoneVerificationService(users, mock(UserService.class),
                new DeliveryComposer("https://web.example", "https://admin.example"), deliveries);
        ReflectionTestUtils.setField(service, "codeMinutes", 10L);
        UserAccount user = UserAccount.builder().email("ada@hospio-qa.test").phone("+2349035911994").build();
        user.setId("u-1");

        service.issue(user, false);

        String text = sent.getValue().text();
        String code = text.replaceAll(".*verification code is (\\d{6}).*", "$1");
        assertThat(code).hasSize(6);
        assertThat(logs.list).isNotEmpty();
        // No argument of any log line is the code (or the phone); the timestamp's digits can't make this flaky.
        assertThat(logs.list).allSatisfy(e -> {
            assertThat(e.getArgumentArray() == null ? List.of() : List.of(e.getArgumentArray()))
                    .noneMatch(a -> String.valueOf(a).contains(code) && !(a instanceof java.time.Instant));
            assertThat(e.getFormattedMessage()).doesNotContain("+2349035911994").doesNotContain("code is " + code);
        });
    }
}

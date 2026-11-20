package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hospio.common.exception.GoneException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.identity.client.TenantClient;
import com.hospio.identity.config.SelfSignUpConfig;
import com.hospio.identity.dto.SignUpRequest;
import com.hospio.identity.repository.RoleGrantRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

/**
 * Self sign-up is off by default (owner decision 2026-11-21): POST /auth/sign-up answers 410 with the "book a demo" sentence and
 * nothing is created; switched on (SELF_SIGNUP_ENABLED=true) it runs as before. Invites never go through this switch.
 */
class SelfSignUpSwitchTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final RoleGrantRepository grants = mock(RoleGrantRepository.class);
    private final TenantClient tenant = mock(TenantClient.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final TokenIssuer tokens = mock(TokenIssuer.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final PhoneVerificationService phones = mock(PhoneVerificationService.class);

    private static SignUpRequest request() {
        return new SignUpRequest("Ada Obi", "ada@hospio-qa.test", "08035550192", "Password123!", "QA Demo Hotel", "Ikeja", null, true, true);
    }

    @Test
    void offRefusesWith410AndTheBookADemoSentenceBeforeTouchingAnything() {
        SignUpService service = new SignUpService(users, grants, tenant, encoder, tokens, events, phones, new SelfSignUpSwitch(false));

        assertThatThrownBy(() -> service.signUp(request()))
                .isInstanceOf(GoneException.class)
                .hasMessage("Hospio360 accounts are set up by our team — book a demo at hospio360.com/book-a-demo");
        verifyNoInteractions(users, grants, tenant, tokens, events, phones);
    }

    @Test
    void onLetsTheSignUpRun() {
        assertThatCode(() -> new SelfSignUpSwitch(true).require()).doesNotThrowAnyException();
        assertThat(new SelfSignUpSwitch(true).enabled()).isTrue();
    }

    @Test
    void theEndpointIsClosedBeforeItsBodyIsReadSoAnEmptyFormGetsTheSameAnswer() throws Exception {
        java.util.List<HandlerInterceptor> added = new java.util.ArrayList<>();
        InterceptorRegistry registry = new InterceptorRegistry() {
            @Override
            public org.springframework.web.servlet.config.annotation.InterceptorRegistration addInterceptor(HandlerInterceptor interceptor) {
                added.add(interceptor);
                return super.addInterceptor(interceptor);
            }
        };
        new SelfSignUpConfig(new SelfSignUpSwitch(false)).addInterceptors(registry);
        assertThat(added).hasSize(1);
        assertThatThrownBy(() -> added.get(0).preHandle(new MockHttpServletRequest("POST", "/auth/sign-up"), new MockHttpServletResponse(), new Object()))
                .isInstanceOf(GoneException.class);

        added.clear();
        new SelfSignUpConfig(new SelfSignUpSwitch(true)).addInterceptors(registry);
        assertThat(added.get(0).preHandle(new MockHttpServletRequest("POST", "/auth/sign-up"), new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void defaultIsOffInEveryProfile() throws Exception {
        String main = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(main).contains("self-serve-enabled: ${SELF_SIGNUP_ENABLED:false}");
        for (String profile : new String[]{"application-dev.yml", "application-staging.yml"}) {
            assertThat(Files.readString(Path.of("src/main/resources/" + profile))).doesNotContain("self-serve-enabled");
        }
        assertThat(new SelfSignUpSwitch(false).enabled()).isFalse();
    }
}

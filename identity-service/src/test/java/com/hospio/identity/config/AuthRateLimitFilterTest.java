package com.hospio.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The per-address backstop on sign-in, PIN sign-in, sign-up and forgot-password. */
class AuthRateLimitFilterTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AuthRateLimitFilter filter = new AuthRateLimitFilter(3, now::get);

    @Test
    void theFourthTryInAMinuteFromOneAddressIs429AndTheNextMinuteIsFreeAgain() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(signIn("41.1.1.1").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse refused = signIn("41.1.1.1");
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getContentAsString()).contains("Too many attempts from this network");
        assertThat(signIn("41.2.2.2").getStatus()).isEqualTo(200);

        now.addAndGet(60_000L);
        assertThat(signIn("41.1.1.1").getStatus()).isEqualTo(200);
    }

    @Test
    void onlyTheGuessingEndpointsAreLimited() {
        MockHttpServletRequest refresh = new MockHttpServletRequest("POST", "/identity/auth/refresh");
        refresh.setContextPath("/identity");
        assertThat(filter.shouldNotFilter(refresh)).isTrue();
        MockHttpServletRequest pin = new MockHttpServletRequest("POST", "/identity/auth/pin-sign-in");
        pin.setContextPath("/identity");
        assertThat(filter.shouldNotFilter(pin)).isFalse();
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/identity/auth/sign-in");
        get.setContextPath("/identity");
        assertThat(filter.shouldNotFilter(get)).isTrue();
    }

    private MockHttpServletResponse signIn(String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/identity/auth/sign-in");
        request.setContextPath("/identity");
        request.addHeader("X-Forwarded-For", forwardedFor + ", 10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}

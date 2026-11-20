package com.hospio.common.security.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.security.AccessRevocations;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.TwoStepSetupPaths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * "Require 2-step sign-in for every admin" (docs/sign-in.md): a staff token limited to setting up 2-step is refused with 403 by
 * every service except the few identity paths of the setup; an ordinary token is untouched.
 */
class JwtFilterTwoStepSetupTest {

    private final JwtUtil jwt = new JwtUtil();
    private final AccessRevocations revocations = mock(AccessRevocations.class);
    private final JwtFilter filter = new JwtFilter(jwt, revocations);

    JwtFilterTwoStepSetupTest() {
        ReflectionTestUtils.setField(jwt, "secret", "test-secret-test-secret-test-secret-test-secret");
        ReflectionTestUtils.setField(jwt, "expirationMs", 60_000L);
        ReflectionTestUtils.invokeMethod(jwt, "buildParser");
        when(revocations.isStale(anyString(), any(), any())).thenReturn(false);
    }

    private String token(boolean setupOnly) {
        return jwt.generateSessionToken("a1", "a@hospio360.com", "Admin", null, List.of(), Map.of(Role.SUPER_ADMIN, AccessLevel.FULL),
                JwtUtil.SCOPE_PLATFORM, "s1", 60_000L, setupOnly);
    }

    /** Runs the filter; true when the request reached the chain. */
    private boolean passes(String token, String uri, MockHttpServletResponse response) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.addHeader("Authorization", "Bearer " + token);
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> reached.set(SecurityContextHolder.getContext().getAuthentication() != null));
        SecurityContextHolder.clearContext();
        return reached.get();
    }

    @Test
    void setupOnlyTokenIsRefusedOnEveryOtherPathAndService() throws Exception {
        for (String uri : List.of("/platform/admin/settings", "/tenant/admin/businesses", "/identity/users", "/identity/support-access",
                "/identity/users/me/sessions", "/identity/users/me/two-step/disable")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(passes(token(true), uri, response)).as(uri).isFalse();
            assertThat(response.getStatus()).as(uri).isEqualTo(403);
            assertThat(response.getContentAsString()).contains(TwoStepSetupPaths.MESSAGE);
            assertThat(response.getHeader("X-Hospio-Two-Step-Setup")).isEqualTo("required");
        }
    }

    @Test
    void setupOnlyTokenReachesTheSetupPaths() throws Exception {
        for (String uri : List.of("/identity/users/me", "/identity/users/me/two-step", "/identity/users/me/two-step/setup",
                "/identity/users/me/two-step/enable", "/identity/auth/change-password", "/identity/auth/logout", "/identity/auth/handoff")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(passes(token(true), uri, response)).as(uri).isTrue();
        }
    }

    @Test
    void ordinaryTokenIsUnchanged() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(passes(token(false), "/platform/admin/settings", response)).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void claimIsReadBack() {
        assertThat(JwtUtil.twoStepSetupOnly(jwt.extractAllClaims(token(true)))).isTrue();
        assertThat(JwtUtil.twoStepSetupOnly(jwt.extractAllClaims(token(false)))).isFalse();
    }
}

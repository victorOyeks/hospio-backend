package com.hospio.common.suspension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** docs/suspension.md: who is refused while a business is suspended, with which code, and what stays open. */
class SuspensionFilterTest {

    private final BusinessSuspension suspension = mock(BusinessSuspension.class);

    @BeforeEach
    void suspended() {
        when(suspension.of("b1")).thenReturn(new BusinessSuspension.Status(true, Instant.parse("2026-11-18T09:00:00Z"), "UNPAID_INVOICE", "Invoices unpaid"));
        when(suspension.of("b2")).thenReturn(BusinessSuspension.Status.NONE);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(HospioUserDetails user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private static HospioUserDetails staff(String business, Role role) {
        return new HospioUserDetails("u1", "ada@x.ng", "Ada", business, List.of("h1"), Map.of(role, AccessLevel.FULL),
                role == Role.OWNER ? JwtUtil.SCOPE_BUSINESS : JwtUtil.SCOPE_HOTEL);
    }

    private MockHttpServletResponse call(String service, String method, String context, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, context + path);
        request.setContextPath(context);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new SuspensionFilter(suspension, service).doFilter(request, response, chain);
        // Passed on = the chain saw the request.
        response.setHeader("X-Passed", chain.getRequest() == null ? "no" : "yes");
        return response;
    }

    @Test
    void everyoneAtASuspendedBusinessIsRefusedWithTheCode() throws Exception {
        for (Role role : List.of(Role.OWNER, Role.MANAGER, Role.FRONT_OFFICE)) {
            signIn(staff("b1", role));
            MockHttpServletResponse r = call("reservation-service", "GET", "/reservation", "/bookings");
            assertThat(r.getStatus()).isEqualTo(403);
            assertThat(r.getHeader(BusinessSuspension.HEADER)).isEqualTo("true");
            assertThat(r.getContentAsString()).contains("\"code\":\"BUSINESS_SUSPENDED\"").contains("\"reason\":\"Invoices unpaid\"")
                    .contains("hello@oyeksglobal.com").contains("\"status\":false");
            assertThat(r.getHeader("X-Passed")).isEqualTo("no");
        }
    }

    @Test
    void signInMeStatusBillingAndLegalStayOpen() throws Exception {
        signIn(staff("b1", Role.OWNER));
        assertThat(call("identity-service", "POST", "/identity", "/auth/refresh").getHeader("X-Passed")).isEqualTo("yes");
        assertThat(call("identity-service", "POST", "/identity", "/auth/logout").getHeader("X-Passed")).isEqualTo("yes");
        assertThat(call("identity-service", "GET", "/identity", "/users/me").getHeader("X-Passed")).isEqualTo("yes");
        assertThat(call("tenant-service", "GET", "/tenant", "/businesses/suspension").getHeader("X-Passed")).isEqualTo("yes");
        assertThat(call("platform-service", "GET", "/platform", "/billing/invoices").getHeader("X-Passed")).isEqualTo("yes");
        assertThat(call("platform-service", "POST", "/platform", "/billing/invoices/i1/pay").getHeader("X-Passed")).isEqualTo("yes");
        assertThat(call("platform-service", "GET", "/platform", "/billing/invoices/i1/payments/p1/receipt").getHeader("X-Passed")).isEqualTo("yes");
        assertThat(call("platform-service", "POST", "/platform", "/legal/accept").getHeader("X-Passed")).isEqualTo("yes");
        assertThat(call("reservation-service", "GET", "/reservation", "/internal/admin/nights").getHeader("X-Passed")).isEqualTo("yes");
    }

    @Test
    void theAllowListIsPerServiceAndPerMethod() throws Exception {
        signIn(staff("b1", Role.OWNER));
        assertThat(call("identity-service", "PUT", "/identity", "/users/me").getStatus()).isEqualTo(403);
        assertThat(call("identity-service", "GET", "/identity", "/users").getStatus()).isEqualTo(403);
        assertThat(call("tenant-service", "PUT", "/tenant", "/businesses/mine").getStatus()).isEqualTo(403);
        assertThat(call("fnb-service", "GET", "/fnb", "/billing/invoices").getStatus()).isEqualTo(403);
        assertThat(call("platform-service", "GET", "/platform", "/fees/daily").getStatus()).isEqualTo(403);
        assertThat(call("folio-service", "GET", "/folio", "/auth/refresh").getStatus()).isEqualTo(403);
    }

    @Test
    void anotherBusinessHospioStaffMarketersSupportViewAndTheSignedOutPass() throws Exception {
        signIn(staff("b2", Role.MANAGER));
        assertThat(call("fnb-service", "POST", "/fnb", "/orders").getHeader("X-Passed")).isEqualTo("yes");

        signIn(new HospioUserDetails("a1", "amoria@hospio360.com", "Amoria", null, List.of(), Map.of(Role.SUPER_ADMIN, AccessLevel.FULL), JwtUtil.SCOPE_PLATFORM));
        assertThat(call("reservation-service", "GET", "/reservation", "/bookings").getHeader("X-Passed")).isEqualTo("yes");

        signIn(new HospioUserDetails("m1", "ada@partner.ng", "Ada", null, List.of(), Map.of(Role.MARKETER, AccessLevel.FULL), JwtUtil.SCOPE_MARKETER));
        assertThat(call("platform-service", "GET", "/platform", "/marketer/me").getHeader("X-Passed")).isEqualTo("yes");

        // Support "view as owner" of the suspended business: read-only already, so Hospio360 can still look.
        signIn(new HospioUserDetails("a2", "kola@hospio360.com", "Kola", "b1", List.of("h1"), Map.of(Role.OWNER, AccessLevel.VIEW), JwtUtil.SCOPE_BUSINESS, "a2"));
        assertThat(call("reservation-service", "GET", "/reservation", "/bookings").getHeader("X-Passed")).isEqualTo("yes");

        SecurityContextHolder.clearContext();
        assertThat(call("reservation-service", "GET", "/reservation", "/public/stays/abc").getHeader("X-Passed")).isEqualTo("yes");
    }
}

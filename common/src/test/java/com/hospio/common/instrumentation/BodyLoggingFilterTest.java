package com.hospio.common.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BodyLoggingFilterTest {

    @Test
    void secretsIncludingTheTwoStepOnesAreMasked() {
        String masked = BodyLoggingFilter.mask("{\"twoStepToken\":\"abc\",\"code\":\"123456\",\"password\":\"p\",\"secret\":\"JBSW\",\"name\":\"Ada\"}");
        assertThat(masked).doesNotContain("abc").doesNotContain("123456").doesNotContain("JBSW").contains("\"name\":\"Ada\"");
    }

    @Test
    void keysAreMatchedByFragmentInAnyCaseAndNumbersAreMaskedToo() {
        String masked = BodyLoggingFilter.mask("{\"newPIN\":1234,\"card_number\":\"4111111111111111\",\"Authorization\":\"Bearer x.y\","
                + "\"guestIdNumber\":\"A123\",\"id_number\":99887766,\"otpCode\":\"9 9\",\"pan\":5399, \"amount\":5000,\"note\":\"pin \\\"1\\\"\"}");
        assertThat(masked).doesNotContain("1234").doesNotContain("4111").doesNotContain("Bearer").doesNotContain("A123")
                .doesNotContain("99887766").doesNotContain("9 9").doesNotContain("5399")
                .contains("\"amount\":5000").contains("\"note\":\"pin \\\"1\\\"\"");
    }

    @Test
    void anErrorResponseIsStreamedThroughAndOnlyItsHeadIsKept() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        BodyLoggingFilter.ErrorBodyCapture capture = new BodyLoggingFilter.ErrorBodyCapture(response);
        capture.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        byte[] big = "x".repeat(5000).getBytes(StandardCharsets.UTF_8);
        capture.getOutputStream().write(big);

        assertThat(response.getContentAsByteArray()).hasSize(5000);
        assertThat(capture.captured()).hasSize(2048);
        assertThat(capture.total()).isEqualTo(5000);
    }

    @Test
    void aSuccessfulResponseIsNotCopied() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        BodyLoggingFilter.ErrorBodyCapture capture = new BodyLoggingFilter.ErrorBodyCapture(response);
        capture.getWriter().write("{\"ok\":true}");
        capture.flushWriter();

        assertThat(response.getContentAsString()).isEqualTo("{\"ok\":true}");
        assertThat(capture.captured()).isEmpty();
    }

    @Test
    void webhooksAreNeverLogged() throws Exception {
        BodyLoggingFilter filter = new BodyLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/payment/webhooks/pos/moniepoint");
        assertThat(filter.shouldNotFilter(request)).isTrue();
        MockHttpServletRequest normal = new MockHttpServletRequest("POST", "/payment/payments");
        assertThat(filter.shouldNotFilter(normal)).isFalse();
        filter.doFilter(normal, new MockHttpServletResponse(), new MockFilterChain());
    }
}

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
    void apiAndProviderKeysAreMaskedAndTheIntegrationSettingsAreNeverLogged() {
        String masked = BodyLoggingFilter.mask("{\"fields\":{\"apiKey\":\"xkeysib-1\",\"webhookKey\":\"wh-1\",\"merchantPrivateKey\":\"MII\","
                + "\"clientAuthKey\":\"cak\",\"fromName\":\"Hospio360\"}}");
        assertThat(masked).doesNotContain("xkeysib-1").doesNotContain("wh-1").doesNotContain("MII").doesNotContain("cak").contains("Hospio360");

        org.springframework.mock.web.MockHttpServletRequest put = new org.springframework.mock.web.MockHttpServletRequest("PUT",
                "/platform/admin/integrations/INFOBIP");
        assertThat(new BodyLoggingFilter().shouldNotFilter(put)).isTrue();
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

    @org.junit.jupiter.api.Test
    void aLongSecretCutShortIsMaskedWithoutOverflowingTheStack() {
        String pem = "-----BEGIN PRIVATE KEY-----\\n" + "MIICdQIBADANBgkqhkiG9w0BAQEFAASCAl8wggJbAgEAAoGBAK".repeat(400);
        String cut = ("{\"provider\":\"OPAY\",\"fields\":{\"merchantPrivateKey\":\"" + pem).substring(0, 2048);
        String masked = BodyLoggingFilter.mask(cut);
        org.assertj.core.api.Assertions.assertThat(masked).contains("\"merchantPrivateKey\":\"***\"").doesNotContain("MIIC");
        // A long value that does close is masked as before, and other fields are left alone.
        String whole = "{\"provider\":\"OPAY\",\"apiKey\":\"" + "x".repeat(50_000) + "\",\"note\":\"hi\"}";
        org.assertj.core.api.Assertions.assertThat(BodyLoggingFilter.mask(whole)).isEqualTo("{\"provider\":\"OPAY\",\"apiKey\":\"***\",\"note\":\"hi\"}");
    }
}

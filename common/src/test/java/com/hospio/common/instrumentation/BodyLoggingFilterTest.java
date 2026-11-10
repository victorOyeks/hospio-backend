package com.hospio.common.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BodyLoggingFilterTest {

    @Test
    void secretsIncludingTheTwoStepOnesAreMasked() {
        String masked = BodyLoggingFilter.mask("{\"twoStepToken\":\"abc\",\"code\":\"123456\",\"password\":\"p\",\"secret\":\"JBSW\",\"name\":\"Ada\"}");
        assertThat(masked).doesNotContain("abc").doesNotContain("123456").doesNotContain("JBSW").contains("\"name\":\"Ada\"");
    }
}

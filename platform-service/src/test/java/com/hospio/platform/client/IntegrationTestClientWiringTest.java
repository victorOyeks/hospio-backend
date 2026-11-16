package com.hospio.platform.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hospio.common.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Spring itself builds the client (it has two constructors: the one for Spring is marked), as at start-up on the server. */
class IntegrationTestClientWiringTest {

    @Test
    void springCanBuildIt() {
        new ApplicationContextRunner()
                .withBean(JwtUtil.class, () -> mock(JwtUtil.class))
                .withUserConfiguration(IntegrationTestClient.class)
                .withPropertyValues("services.messaging.url=http://localhost:1/messaging", "services.payment.url=http://localhost:1/payment",
                        "services.internal.api-key=test-key")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(IntegrationTestClient.class);
                });
    }
}

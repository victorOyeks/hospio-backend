package com.hospio.platform.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.hospio.common.http.HttpTimeouts;
import com.hospio.common.security.JwtUtil;
import com.hospio.platform.dto.IntegrationDtos.ServiceCheck;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

/** GET /internal/integrations/status on messaging-service and payment-service, read as typed rows, with a short timeout. */
class ConnectionCheckClientTest {

    private HttpServer server;
    private volatile String messagingBody;
    private volatile int messagingStatus = 200;
    private volatile long delayMs;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/messaging/internal/integrations/status", ex -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = (messagingBody == null ? "{}" : messagingBody).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(messagingStatus, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/payment/internal/integrations/status", ex -> {
            byte[] out = "{\"status\":true,\"data\":{\"key\":\"payment-provider\",\"status\":\"TEST_MODE\",\"detail\":\"Test provider: nothing is charged.\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ConnectionCheckClient client(Duration read) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ConnectionCheckClient(HttpTimeouts.builder(Duration.ofSeconds(1), read).baseUrl(base + "/messaging").build(),
                HttpTimeouts.builder(Duration.ofSeconds(1), read).baseUrl(base + "/payment").build());
    }

    @Test
    void rowsAreReadTyped() {
        messagingBody = "{\"status\":true,\"data\":[{\"key\":\"sms\",\"status\":\"CONNECTED\",\"detail\":\"Termii, sender ID OE Alert\"}]}";
        assertThat(client(Duration.ofSeconds(2)).messaging()).containsExactly(new ServiceCheck("sms", "CONNECTED", "Termii, sender ID OE Alert"));
        assertThat(client(Duration.ofSeconds(2)).payment()).isEqualTo(new ServiceCheck("payment-provider", "TEST_MODE", "Test provider: nothing is charged."));
    }

    @Test
    void anOlderServiceAnswers404() {
        messagingStatus = 404;
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).messaging()).isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void aSlowServiceTimesOut() {
        delayMs = 1500;
        messagingBody = "{\"data\":[]}";
        assertThatThrownBy(() -> client(Duration.ofMillis(300)).messaging()).isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void springCanBuildIt() {
        new ApplicationContextRunner()
                .withBean(JwtUtil.class, () -> mock(JwtUtil.class))
                .withUserConfiguration(ConnectionCheckClient.class)
                .withPropertyValues("services.messaging.url=http://localhost:1/messaging", "services.payment.url=http://localhost:1/payment",
                        "services.internal.api-key=test-key")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(ConnectionCheckClient.class));
    }
}

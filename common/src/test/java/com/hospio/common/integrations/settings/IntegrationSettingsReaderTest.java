package com.hospio.common.integrations.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The reader the services use: nothing saved or platform-service down means the environment's values exactly; a saved value wins;
 * answers are cached; a short outage keeps the last settings read.
 */
class IntegrationSettingsReaderTest {

    private static final IntegrationSettings.Paystack ENV = new IntegrationSettings.Paystack("sk_test_env");

    private HttpServer server;
    private final AtomicReference<String> answer = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            lastPath.set(exchange.getRequestURI().toString());
            byte[] body = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private IntegrationSettingsReader reader() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new IntegrationSettingsReader(RestClient.builder().baseUrl(url).build());
    }

    @Test
    void nothingSavedIsTheEnvironmentExactly() {
        answer.set("{\"status\":true,\"message\":\"OK\",\"data\":null}");
        IntegrationSettingsReader reader = reader();

        assertThat(reader.effective(ENV)).isSameAs(ENV);
        assertThat(lastPath.get()).isEqualTo("/internal/integrations/PAYSTACK");
        reader.effective(ENV);
        assertThat(hits).as("an empty answer is cached too").hasValue(1);
    }

    @Test
    void aValueSavedInThePortalWinsOverTheEnvironment() {
        answer.set("{\"status\":true,\"data\":{\"secretKey\":\"sk_live_portal\"}}");
        assertThat(reader().effective(ENV).secretKey()).isEqualTo("sk_live_portal");

        answer.set("{\"status\":true,\"data\":{\"baseUrl\":\"https://portal.api.infobip.com\",\"smsEnabled\":false}}");
        IntegrationSettings.Infobip env = new IntegrationSettings.Infobip("https://env", "k", null, null, null, null, true, true);
        IntegrationSettings.Infobip in = reader().effective(env);
        assertThat(in.baseUrl()).isEqualTo("https://portal.api.infobip.com");
        assertThat(in.apiKey()).isEqualTo("k");
        assertThat(in.smsEnabled()).isFalse();
        assertThat(in.whatsappEnabled()).isTrue();
    }

    @Test
    void platformDownWithNothingReadBeforeIsTheEnvironment() {
        status.set(503);
        answer.set("{\"status\":false,\"message\":\"down\"}");
        assertThat(reader().effective(ENV)).isSameAs(ENV);

        IntegrationSettingsReader unreachable = new IntegrationSettingsReader(RestClient.builder().baseUrl("http://127.0.0.1:1").build());
        assertThat(unreachable.effective(ENV)).isSameAs(ENV);
    }

    @Test
    void aShortOutageKeepsTheLastSettingsRead() {
        answer.set("{\"status\":true,\"data\":{\"secretKey\":\"sk_live_portal\"}}");
        IntegrationSettingsReader reader = reader();
        assertThat(reader.effective(ENV).secretKey()).isEqualTo("sk_live_portal");

        status.set(500);
        assertThat(reader.fresh(IntegrationSettings.Paystack.class)).as("platform-service failing").get()
                .extracting(IntegrationSettings.Paystack::secretKey).isEqualTo("sk_live_portal");
        assertThat(reader.effective(ENV).secretKey()).isEqualTo("sk_live_portal");
    }

    @Test
    void freshAsksAgainAndUpdatesTheCache() {
        answer.set("{\"status\":true,\"data\":{\"secretKey\":\"sk_live_one\"}}");
        IntegrationSettingsReader reader = reader();
        assertThat(reader.effective(ENV).secretKey()).isEqualTo("sk_live_one");

        answer.set("{\"status\":true,\"data\":{\"secretKey\":\"sk_live_two\"}}");
        assertThat(reader.effective(ENV).secretKey()).as("cached for a minute").isEqualTo("sk_live_one");
        reader.fresh(IntegrationSettings.Paystack.class);
        assertThat(reader.effective(ENV).secretKey()).isEqualTo("sk_live_two");
        assertThat(hits).hasValue(2);
    }
}

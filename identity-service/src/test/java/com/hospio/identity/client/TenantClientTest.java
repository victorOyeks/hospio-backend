package com.hospio.identity.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hospio.common.security.JwtUtil;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** identity's typed reads of tenant: a business's hotel ids, one hotel, a business name; empty when tenant can't answer. */
class TenantClientTest {

    private HttpServer server;
    private final Map<String, Object[]> answers = new ConcurrentHashMap<>();
    private TenantClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Object[] a = answers.getOrDefault(ex.getRequestURI().getPath(), new Object[] {500, "{}"});
            byte[] out = ((String) a[1]).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders((int) a[0], out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        client = new TenantClient("http://127.0.0.1:" + server.getAddress().getPort(), mock(JwtUtil.class), "k");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(String path, String data) {
        answers.put(path, new Object[] {200, "{\"status\":true,\"message\":\"OK\",\"requestTime\":\"2026-11-15T10:00:00Z\",\"data\":" + data + "}"});
    }

    @Test
    void readsHotelsAndNames() {
        answer("/internal/businesses/b1/hotels", "[{\"id\":\"h1\",\"businessId\":\"b1\",\"name\":\"A\",\"roomCount\":3,\"status\":\"ACTIVE\"},"
                + "{\"id\":\"h2\",\"businessId\":\"b1\",\"name\":\"B\"}]");
        answer("/internal/hotels/h1", "{\"id\":\"h1\",\"businessId\":\"b1\",\"name\":\"A\",\"createdAt\":\"2026-10-01T09:00:00Z\"}");
        answer("/internal/hotels/h3", "null");
        answer("/internal/businesses/b1", "{\"id\":\"b1\",\"name\":\"Royal Suites\",\"hotels\":[],\"onboarding\":null}");
        assertThat(client.hotelIds("b1")).contains(List.of("h1", "h2"));
        assertThat(client.hotel("h1")).contains(new TenantClient.HotelInfo("h1", "b1", "A"));
        assertThat(client.hotel("h3")).isEmpty();
        assertThat(client.businessName("b1")).contains("Royal Suites");
    }

    @Test
    void anUnreachableTenantIsEmpty() {
        assertThat(client.hotelIds("b1")).isEmpty();
        assertThat(client.hotel("h1")).isEmpty();
        assertThat(client.businessName("b1")).isEmpty();
    }
}

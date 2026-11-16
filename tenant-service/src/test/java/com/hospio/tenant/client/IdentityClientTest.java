package com.hospio.tenant.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.hospio.common.delivery.Delivery;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.security.JwtUtil;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** tenant's typed reads of identity: the created login and its deliveries, taken sign-ins, names; and what each gives on a failure. */
class IdentityClientTest {

    private HttpServer server;
    private final Map<String, Object[]> answers = new ConcurrentHashMap<>();
    private IdentityClient client;

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
        client = new IdentityClient("http://127.0.0.1:" + server.getAddress().getPort(), mock(JwtUtil.class), "k",
                JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(String path, String data) {
        answers.put(path, new Object[] {200, "{\"status\":true,\"message\":\"OK\",\"data\":" + data + "}"});
    }

    @Test
    void aCreatedLoginReadsWithItsDeliveries() {
        answer("/internal/users", "{\"userId\":\"u1\",\"temporaryPassword\":\"T3mp\",\"temporaryPasswordExpiresAt\":\"2026-11-20T10:00:00Z\","
                + "\"inviteToken\":\"inv\",\"inviteExpiresAt\":\"2026-11-20T10:00:00Z\",\"deliveries\":[{\"channel\":\"EMAIL\",\"to\":\"o@x.ng\","
                + "\"subject\":\"Welcome\",\"text\":\"t\",\"kind\":\"INVITE\",\"status\":\"EMAIL_SENT\"}]}");
        IdentityClient.CreatedLogin login = client.createUser("Ola", "o@x.ng", null, "OWNER", null, "b1");
        assertThat(login.userId()).isEqualTo("u1");
        assertThat(login.expiresAt()).isEqualTo(Instant.parse("2026-11-20T10:00:00Z"));
        assertThat(login.deliveries()).singleElement().satisfies(d -> {
            assertThat(d.kind()).isEqualTo(Delivery.Kind.INVITE);
            assertThat(d.status()).isEqualTo("EMAIL_SENT");
        });
    }

    @Test
    void unreadableDeliveriesNeverFailTheLogin() {
        answer("/internal/users/u1/resend-invite", "{\"userId\":\"u1\",\"temporaryPassword\":\"T3mp\",\"deliveries\":[{\"channel\":\"PIGEON\"}]}");
        IdentityClient.CreatedLogin login = client.resendInvite("u1");
        assertThat(login.userId()).isEqualTo("u1");
        assertThat(login.deliveries()).isEmpty();
        assertThat(login.inviteToken()).isNull();
    }

    @Test
    void noUserOrAFailureIsAnExternalServiceError() {
        answer("/internal/users", "null");
        assertThatThrownBy(() -> client.createUser("Ola", "o@x.ng", null, "OWNER", null, "b1")).isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("returned no user");
        answers.remove("/internal/users");
        assertThatThrownBy(() -> client.createUser("Ola", "o@x.ng", null, "OWNER", null, "b1")).isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("right now");
    }

    @Test
    void takenAndNamesReadAndFailSoft() {
        assertThat(client.taken("o@x.ng", null)).isEqualTo(new IdentityClient.Taken(false, false));
        assertThat(client.names(List.of("u1"))).isEmpty();
        answer("/internal/users/taken", "{\"email\":true}");
        answer("/internal/users/contacts", "[{\"id\":\"u1\",\"fullName\":\"Grace\",\"roles\":[]},{\"id\":\"u2\",\"fullName\":null}]");
        assertThat(client.taken("o@x.ng", "0803")).isEqualTo(new IdentityClient.Taken(true, false));
        assertThat(client.names(List.of("u1", "u2"))).containsExactly(Map.entry("u1", "Grace"));
    }
}

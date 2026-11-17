package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hospio.platform.client.ConnectionCheckClient;
import com.hospio.platform.dto.AdminResponses.Integration;
import com.hospio.platform.dto.IntegrationDtos.ServiceCheck;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Connection checks: every row comes from the service that owns it, the checks run side by side, and one slow or failing service
 * turns only its own rows into ERROR with a readable reason.
 */
class IntegrationsServiceTest {

    private JdbcTemplate jdbc;
    private ConnectionCheckClient services;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        services = mock(ConnectionCheckClient.class);
    }

    /** Kafka answered by a stand-in (a real AdminClient would wait for a broker). */
    private IntegrationsService service(long timeoutMs, long kafkaMs) {
        return new IntegrationsService(jdbc, "localhost:9092", services, timeoutMs) {
            @Override
            Integration kafka() {
                sleep(kafkaMs);
                return new Integration("kafka", "Kafka", "EVENTS", "CONNECTED", "1 broker at localhost:9092", Instant.now(), "platform-service");
            }
        };
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, Integration> byKey(List<Integration> rows) {
        return rows.stream().collect(Collectors.toMap(Integration::key, Function.identity()));
    }

    private static List<ServiceCheck> allConnected() {
        return List.of(new ServiceCheck("whatsapp", "CONNECTED", "Infobip, sender +2348148849797, key present."),
                new ServiceCheck("email", "CONNECTED", "Brevo API, key present."),
                new ServiceCheck("sms", "CONNECTED", "Sign-in codes only. Termii, sender ID OE Alert, channel dnd."));
    }

    @Test
    void eachRowIsWhatItsServiceReportedInTheUsualOrder() {
        when(services.messaging()).thenReturn(allConnected());
        when(services.payment()).thenReturn(new ServiceCheck("payment-provider", "TEST_MODE", "Test provider: nothing is charged."));

        List<Integration> rows = service(3000, 0).list();

        assertThat(rows).extracting(Integration::key).containsExactly("database", "kafka", "payment-provider", "whatsapp", "email", "sms");
        Map<String, Integration> r = byKey(rows);
        assertThat(r.get("payment-provider").status()).isEqualTo("TEST_MODE");
        assertThat(r.get("payment-provider").checkedBy()).isEqualTo("payment-service");
        assertThat(r.get("sms").name()).isEqualTo("SMS (sign-in codes only)");
        assertThat(r.get("sms").detail()).contains("OE Alert", "dnd");
        assertThat(r.get("sms").checkedBy()).isEqualTo("messaging-service");
        assertThat(r.get("database").checkedBy()).isEqualTo("platform-service");
        assertThat(rows).allSatisfy(i -> assertThat(i.lastCheck()).isNotNull());
    }

    @Test
    void aSlowServiceIsAnErrorForItsOwnRowsOnlyAndTheChecksRunSideBySide() {
        when(services.messaging()).thenAnswer(a -> {
            sleep(3000);
            return allConnected();
        });
        when(services.payment()).thenAnswer(a -> {
            sleep(200);
            return new ServiceCheck("payment-provider", "TEST_MODE", "Test provider: nothing is charged.");
        });

        long t0 = System.nanoTime();
        Map<String, Integration> r = byKey(service(500, 200).list());
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(ms).as("parallel, bounded by the timeout").isLessThan(1500);
        assertThat(r.get("whatsapp").status()).isEqualTo("ERROR");
        assertThat(r.get("whatsapp").detail()).isEqualTo("Not reachable: messaging-service didn't answer within 0.5 s");
        assertThat(r.get("email").status()).isEqualTo("ERROR");
        assertThat(r.get("sms").status()).isEqualTo("ERROR");
        assertThat(r.get("payment-provider").status()).isEqualTo("TEST_MODE");
        assertThat(r.get("database").status()).isEqualTo("CONNECTED");
        assertThat(r.get("kafka").status()).isEqualTo("CONNECTED");
    }

    @Test
    void anOlderServiceWithoutTheCheckIsNotReachable() {
        when(services.messaging()).thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));
        when(services.payment()).thenThrow(new ResourceAccessException("I/O error", new java.net.ConnectException("Connection refused")));

        Map<String, Integration> r = byKey(service(3000, 0).list());

        assertThat(r.get("email").status()).isEqualTo("ERROR");
        assertThat(r.get("email").detail()).isEqualTo("Not reachable: messaging-service doesn't offer this check yet (an older version is running)");
        assertThat(r.get("payment-provider").detail()).isEqualTo("Not reachable: payment-service isn't running or refused the connection");
        assertThat(r.get("database").status()).isEqualTo("CONNECTED");
    }

    @Test
    void readTimeoutsAndServerErrorsAreSaidInWords() {
        assertThat(IntegrationsService.why("payment-service", new ResourceAccessException("x", new SocketTimeoutException("Read timed out"))))
                .isEqualTo("Not reachable: payment-service didn't answer in time");
        assertThat(IntegrationsService.why("payment-service", HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "x", null, null, null)))
                .isEqualTo("Not reachable: payment-service answered with an error (HTTP 500)");
        assertThat(IntegrationsService.why("payment-service", new IllegalStateException("token secret-123")))
                .isEqualTo("Not reachable: payment-service couldn't be asked (IllegalStateException)");
    }

    @Test
    void aMissingRowOrAnUnknownStatusIsAnError() {
        when(services.messaging()).thenReturn(List.of(new ServiceCheck("whatsapp", "SOMETHING_NEW", "?"),
                new ServiceCheck("email", "NOT_CONFIGURED", "Email is switched off, so nothing is sent.")));
        when(services.payment()).thenReturn(null);

        Map<String, Integration> r = byKey(service(3000, 0).list());

        assertThat(r.get("whatsapp").status()).isEqualTo("ERROR");
        assertThat(r.get("email").status()).isEqualTo("NOT_CONFIGURED");
        assertThat(r.get("sms").status()).isEqualTo("ERROR");
        assertThat(r.get("sms").detail()).isEqualTo("messaging-service didn't report this check");
        assertThat(r.get("payment-provider").detail()).isEqualTo("payment-service gave no answer");
    }

    @Test
    void aDatabaseFailureShowsItsKindNotItsMessage() {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("password=hunter2"));
        when(services.messaging()).thenReturn(allConnected());
        Map<String, Integration> r = byKey(service(3000, 0).list());
        assertThat(r.get("database").status()).isEqualTo("ERROR");
        assertThat(r.get("database").detail()).isEqualTo("Not reachable: DataAccessResourceFailureException").doesNotContain("hunter2");
    }
}

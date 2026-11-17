package com.hospio.platform.service;

import com.hospio.platform.client.ConnectionCheckClient;
import com.hospio.platform.dto.AdminResponses.Integration;
import com.hospio.platform.dto.IntegrationDtos.ServiceCheck;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Settings · Integrations → Connection checks (docs/admin-portal.md): each row is checked by the service that owns it, every time
 * the list is asked for. platform-service checks PostgreSQL (SELECT 1) and Kafka (describeCluster); messaging-service reports
 * WhatsApp, email and SMS and payment-service the POS provider (GET /internal/integrations/status: keys present or not, never their
 * values; switches; sender; a read-only call to the provider; nothing is ever sent or charged). The four checks run in parallel,
 * each within {@code timeout}; one that is slow or fails gives its own rows ERROR with the reason, never the whole list.
 */
@Slf4j
@Service
public class IntegrationsService {

    private static final ExecutorService CHECKS = Executors.newVirtualThreadPerTaskExecutor();
    private static final Set<String> STATUSES = Set.of("CONNECTED", "NOT_CONFIGURED", "TEST_MODE", "ERROR");
    private static final String PLATFORM = "platform-service";
    private static final String MESSAGING = "messaging-service";
    private static final String PAYMENT = "payment-service";

    /** The messaging rows in the order the page shows them, with their names. */
    private static final Map<String, String> MESSAGING_ROWS = new LinkedHashMap<>();

    static {
        MESSAGING_ROWS.put("whatsapp", "WhatsApp");
        MESSAGING_ROWS.put("email", "Email");
        MESSAGING_ROWS.put("sms", "SMS (sign-in codes only)");
    }

    private final JdbcTemplate jdbc;
    private final String kafkaServers;
    private final ConnectionCheckClient services;
    private final long timeoutMs;

    @Autowired
    public IntegrationsService(JdbcTemplate jdbc, @Value("${spring.kafka.bootstrap-servers}") String kafkaServers, ConnectionCheckClient services,
                               @Value("${hospio.integrations.list-timeout-ms:6000}") long timeoutMs) {
        this.jdbc = jdbc;
        this.kafkaServers = kafkaServers;
        this.services = services;
        this.timeoutMs = timeoutMs;
    }

    public List<Integration> list() {
        CompletableFuture<List<Integration>> database = run(() -> List.of(database()), () -> List.of(late("database", "PostgreSQL", "DATABASE", PLATFORM)));
        CompletableFuture<List<Integration>> kafka = run(() -> List.of(kafka()), () -> List.of(late("kafka", "Kafka", "EVENTS", PLATFORM)));
        CompletableFuture<List<Integration>> messaging = run(this::messaging, () -> messagingUnreachable("didn't answer within " + seconds(timeoutMs)));
        CompletableFuture<List<Integration>> payment = run(() -> List.of(payment()),
                () -> List.of(row("payment-provider", "Payment provider (POS)", "PAYMENTS", "ERROR",
                        "Not reachable: " + PAYMENT + " didn't answer within " + seconds(timeoutMs), PAYMENT)));
        List<Integration> out = new ArrayList<>();
        out.addAll(database.join());
        out.addAll(kafka.join());
        out.addAll(payment.join());
        out.addAll(messaging.join());
        return out;
    }

    private CompletableFuture<List<Integration>> run(Supplier<List<Integration>> check, Supplier<List<Integration>> onTimeout) {
        CompletableFuture<List<Integration>> f = CompletableFuture.supplyAsync(check, CHECKS);
        // completeOnTimeout takes a value, not a supplier: the time on a late row is set when the deadline passes.
        CompletableFuture<List<Integration>> deadline = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS, CHECKS).execute(() -> deadline.complete(onTimeout.get()));
        return f.applyToEither(deadline, r -> r);
    }

    Integration database() {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            return row("database", "PostgreSQL", "DATABASE", one != null && one == 1 ? "CONNECTED" : "ERROR", "Row-level security on every tenant table", PLATFORM);
        } catch (Exception e) {
            return row("database", "PostgreSQL", "DATABASE", "ERROR", "Not reachable: " + e.getClass().getSimpleName(), PLATFORM);
        }
    }

    Integration kafka() {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServers, AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000))) {
            int nodes = admin.describeCluster().nodes().get(4, TimeUnit.SECONDS).size();
            return row("kafka", "Kafka", "EVENTS", nodes > 0 ? "CONNECTED" : "ERROR", nodes + (nodes == 1 ? " broker" : " brokers") + " at " + kafkaServers, PLATFORM);
        } catch (Exception e) {
            return row("kafka", "Kafka", "EVENTS", "ERROR", "Not reachable at " + kafkaServers + ": " + e.getClass().getSimpleName(), PLATFORM);
        }
    }

    List<Integration> messaging() {
        List<ServiceCheck> got;
        try {
            got = services.messaging();
        } catch (Exception e) {
            return messagingUnreachable(why(MESSAGING, e));
        }
        List<Integration> out = new ArrayList<>();
        MESSAGING_ROWS.forEach((key, name) -> out.add(got.stream().filter(c -> c != null && key.equals(c.key())).findFirst()
                .map(c -> row(key, name, "MESSAGING", status(c.status()), c.detail(), MESSAGING))
                .orElseGet(() -> row(key, name, "MESSAGING", "ERROR", MESSAGING + " didn't report this check", MESSAGING))));
        return out;
    }

    Integration payment() {
        try {
            ServiceCheck c = services.payment();
            if (c == null) {
                return row("payment-provider", "Payment provider (POS)", "PAYMENTS", "ERROR", PAYMENT + " gave no answer", PAYMENT);
            }
            return row("payment-provider", "Payment provider (POS)", "PAYMENTS", status(c.status()), c.detail(), PAYMENT);
        } catch (Exception e) {
            return row("payment-provider", "Payment provider (POS)", "PAYMENTS", "ERROR", why(PAYMENT, e), PAYMENT);
        }
    }

    private List<Integration> messagingUnreachable(String why) {
        String detail = why.startsWith("Not reachable") ? why : "Not reachable: " + MESSAGING + " " + why;
        List<Integration> out = new ArrayList<>();
        MESSAGING_ROWS.forEach((key, name) -> out.add(row(key, name, "MESSAGING", "ERROR", detail, MESSAGING)));
        return out;
    }

    /** Why a service couldn't be asked, in words: an older version without the check, a timeout, refused, or its error. */
    static String why(String service, Exception e) {
        Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        if (cause instanceof HttpStatusCodeException h) {
            int code = h.getStatusCode().value();
            if (code == 404) {
                return "Not reachable: " + service + " doesn't offer this check yet (an older version is running)";
            }
            return "Not reachable: " + service + " answered with an error (HTTP " + code + ")";
        }
        if (cause instanceof ResourceAccessException r) {
            return r.getCause() instanceof SocketTimeoutException ? "Not reachable: " + service + " didn't answer in time"
                    : "Not reachable: " + service + " isn't running or refused the connection";
        }
        log.warn("Connection check through {} failed: {}", service, cause.toString());
        return "Not reachable: " + service + " couldn't be asked (" + cause.getClass().getSimpleName() + ")";
    }

    private Integration late(String key, String name, String kind, String service) {
        return row(key, name, kind, "ERROR", "No answer within " + seconds(timeoutMs), service);
    }

    private static String status(String s) {
        return s != null && STATUSES.contains(s) ? s : "ERROR";
    }

    private static Integration row(String key, String name, String kind, String status, String detail, String service) {
        return new Integration(key, name, kind, status, detail, Instant.now(), service);
    }

    private static String seconds(long ms) {
        return (ms % 1000 == 0 ? String.valueOf(ms / 1000) : String.valueOf(ms / 1000.0)) + " s";
    }
}

package com.hospio.platform.service;

import com.hospio.platform.dto.AdminResponses.Integration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Settings · Integrations: what the platform is connected to, from live checks (database, Kafka) and
 * configuration (POS provider, message channels, KYC). Staging runs the "logging" providers.
 */
@Service
public class IntegrationsService {

    private final JdbcTemplate jdbc;
    private final String kafkaServers;
    private final String posProvider;
    private final String channelsProvider;

    public IntegrationsService(JdbcTemplate jdbc, @Value("${spring.kafka.bootstrap-servers}") String kafkaServers,
                               @Value("${hospio.pos.provider:logging}") String posProvider, @Value("${hospio.channels.provider:logging}") String channelsProvider) {
        this.jdbc = jdbc;
        this.kafkaServers = kafkaServers;
        this.posProvider = posProvider;
        this.channelsProvider = channelsProvider;
    }

    public List<Integration> list() {
        Instant now = Instant.now();
        List<Integration> out = new ArrayList<>();
        out.add(database(now));
        out.add(kafka(now));
        boolean realPos = !"logging".equalsIgnoreCase(posProvider);
        out.add(new Integration("payment-provider", "Payment provider (POS)", "PAYMENTS", realPos ? "CONNECTED" : "CONNECTED",
                realPos ? posProvider : "Logging provider: card and transfer requests are accepted and settled by Verify or webhook; nothing is charged", now));
        boolean realChannels = !"logging".equalsIgnoreCase(channelsProvider);
        String channelDetail = realChannels ? channelsProvider : "Not connected: messages are returned in API responses and shown on screen";
        out.add(new Integration("whatsapp", "WhatsApp", "MESSAGING", realChannels ? "CONNECTED" : "NOT_CONFIGURED", channelDetail, now));
        out.add(new Integration("email", "Email", "MESSAGING", realChannels ? "CONNECTED" : "NOT_CONFIGURED", channelDetail, now));
        out.add(new Integration("sms", "SMS", "MESSAGING", realChannels ? "CONNECTED" : "NOT_CONFIGURED", channelDetail, now));
        out.add(new Integration("smile-id", "Smile ID (KYC)", "KYC", "NOT_CONFIGURED", "Identity verification is not set up yet", now));
        return out;
    }

    private Integration database(Instant now) {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            return new Integration("database", "PostgreSQL", "DATABASE", one != null && one == 1 ? "CONNECTED" : "ERROR", "Row-level security on every tenant table", now);
        } catch (Exception e) {
            return new Integration("database", "PostgreSQL", "DATABASE", "ERROR", e.getMessage(), now);
        }
    }

    private Integration kafka(Instant now) {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServers, AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000))) {
            int nodes = admin.describeCluster().nodes().get(4, TimeUnit.SECONDS).size();
            return new Integration("kafka", "Kafka", "EVENTS", nodes > 0 ? "CONNECTED" : "ERROR", nodes + (nodes == 1 ? " broker" : " brokers") + " at " + kafkaServers, now);
        } catch (Exception e) {
            return new Integration("kafka", "Kafka", "EVENTS", "ERROR", "Not reachable at " + kafkaServers + ": " + e.getMessage(), now);
        }
    }
}

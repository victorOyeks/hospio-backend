package com.hospio.common.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * JSON producer shared by every service; events are keyed by hotel id so one hotel's events stay ordered.
 *
 * Idempotent (a retried send is not written twice and keeps its order). A send never blocks the caller for more than
 * five seconds when the broker is down ({@code max.block.ms}, the metadata wait) and gives up after thirty
 * ({@code delivery.timeout.ms}); the default sixty-second block held request threads when Kafka was unreachable.
 */
@Configuration
public class KafkaProducerConfig {

    public static final int MAX_BLOCK_MS = 5_000;
    public static final int REQUEST_TIMEOUT_MS = 15_000;
    /** Must be at least linger.ms + request.timeout.ms, or the producer refuses to start. */
    public static final int DELIVERY_TIMEOUT_MS = 30_000;
    public static final int LINGER_MS = 5;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /** The settings every Hospio producer uses, without serializers. */
    public static Map<String, Object> producerProperties(String bootstrapServers) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MS);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, REQUEST_TIMEOUT_MS);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, DELIVERY_TIMEOUT_MS);
        config.put(ProducerConfig.LINGER_MS_CONFIG, LINGER_MS);
        return config;
    }

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> config = producerProperties(bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}

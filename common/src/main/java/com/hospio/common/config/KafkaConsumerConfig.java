package com.hospio.common.config;

import com.hospio.common.kafka.KafkaConsumerDefaults;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Consumer wiring for every service that sets {@code spring.kafka.consumer.group-id}. Failure behaviour
 * (poison pills, retries, the dead-letter topics, cooperative rebalancing, stall detection) comes from
 * {@link KafkaConsumerDefaults}. {@code hospio.kafka.concurrency} (default 1) sets the consumer threads per listener.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty("spring.kafka.consumer.group-id")
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    @Value("${hospio.kafka.concurrency:1}")
    private int concurrency;

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> config = KafkaConsumerDefaults.consumerConfig(bootstrapServers, autoOffsetReset);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
        return KafkaConsumerDefaults.listenerContainerFactory(consumerFactory(), deadLetterTemplate(), concurrency);
    }

    /**
     * Publishes failed records to their dead-letter topic: the event as JSON, or the original bytes when the record could
     * not be read at all (they arrive as {@code byte[]}). Not a bean, so it never competes with the event template.
     */
    private KafkaTemplate<String, Object> deadLetterTemplate() {
        Map<Class<?>, Serializer<?>> byType = new LinkedHashMap<>();
        byType.put(byte[].class, new ByteArraySerializer());
        byType.put(Object.class, new JsonSerializer<>());
        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(
                KafkaProducerConfig.producerProperties(bootstrapServers), new StringSerializer(), new DelegatingByTypeSerializer(byType, true));
        return new KafkaTemplate<>(factory);
    }
}

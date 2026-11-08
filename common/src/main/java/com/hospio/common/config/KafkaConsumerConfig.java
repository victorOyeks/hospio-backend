package com.hospio.common.config;

import com.hospio.common.kafka.KafkaConsumerDefaults;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

/**
 * Consumer wiring for every service that sets {@code spring.kafka.consumer.group-id}. Failure behaviour
 * (poison pills, cooperative rebalancing, stall detection) comes from {@link KafkaConsumerDefaults}.
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

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> config = KafkaConsumerDefaults.consumerConfig(bootstrapServers, autoOffsetReset);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
        return KafkaConsumerDefaults.listenerContainerFactory(consumerFactory());
    }
}

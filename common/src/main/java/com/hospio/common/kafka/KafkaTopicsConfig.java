package com.hospio.common.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Declares every topic so Spring's KafkaAdmin provisions it at startup, on whichever service boots
 * first, instead of relying on the broker's lazy auto-create.
 */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    public KafkaAdmin.NewTopics hospioTopics() {
        return new KafkaAdmin.NewTopics(KafkaTopics.ALL.stream()
                .map(name -> TopicBuilder.name(name).partitions(3).replicas(1).build())
                .toArray(NewTopic[]::new));
    }
}

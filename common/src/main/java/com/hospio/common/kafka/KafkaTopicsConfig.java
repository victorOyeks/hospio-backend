package com.hospio.common.kafka;

import java.util.stream.Stream;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Declares every topic so Spring's KafkaAdmin provisions it at startup, on whichever service boots
 * first, instead of relying on the broker's lazy auto-create, and its dead-letter topic ({@code <topic>.DLT}, one
 * partition: it only holds records that failed every retry).
 *
 * Partitions come from {@code hospio.kafka.partitions} (default 6). On an existing topic with fewer partitions
 * KafkaAdmin raises the count at startup (it logs "exists but has a different partition count ... increasing");
 * it never lowers it. Raising it moves some hotels' keys to new partitions, so for the moment of the change one
 * hotel's events published just before and just after may be consumed out of order.
 */
@Configuration
public class KafkaTopicsConfig {

    @Value("${hospio.kafka.partitions:6}")
    private int partitions = 6;

    @Value("${hospio.kafka.replicas:1}")
    private short replicas = 1;

    @Bean
    public KafkaAdmin.NewTopics hospioTopics() {
        return new KafkaAdmin.NewTopics(KafkaTopics.ALL.stream()
                .flatMap(name -> Stream.of(
                        TopicBuilder.name(name).partitions(partitions).replicas(replicas).build(),
                        TopicBuilder.name(name + KafkaConsumerDefaults.DLT_SUFFIX).partitions(1).replicas(replicas).build()))
                .toArray(NewTopic[]::new));
    }
}

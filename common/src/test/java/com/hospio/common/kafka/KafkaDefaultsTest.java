package com.hospio.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hospio.common.config.KafkaProducerConfig;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;

class KafkaDefaultsTest {

    @Test
    @SuppressWarnings("unchecked")
    void listenersGetTheConfiguredConcurrencyAndADeadLetterHandler() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = KafkaConsumerDefaults.listenerContainerFactory(
                mock(ConsumerFactory.class), mock(KafkaOperations.class), 3);
        assertThat(factory.getContainerProperties()).isNotNull();
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(factory, "concurrency")).isEqualTo(3);
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(factory, "commonErrorHandler")).isNotNull();
    }

    @Test
    void everyTopicHasADeadLetterTopicAndTheConfiguredPartitions() {
        KafkaTopicsConfig config = new KafkaTopicsConfig();
        org.springframework.test.util.ReflectionTestUtils.setField(config, "partitions", 6);
        Map<String, NewTopic> topics = new java.util.HashMap<>();
        for (NewTopic t : (java.util.Collection<NewTopic>) org.springframework.test.util.ReflectionTestUtils.invokeMethod(config.hospioTopics(), "getNewTopics")) {
            topics.put(t.name(), t);
        }
        for (String name : KafkaTopics.ALL) {
            assertThat(topics.get(name).numPartitions()).isEqualTo(6);
            assertThat(topics).containsKey(name + ".DLT");
        }
    }

    @Test
    void theProducerNeverBlocksARequestForLongAndStartsWithConsistentTimeouts() {
        Map<String, Object> p = KafkaProducerConfig.producerProperties("localhost:9092");
        assertThat(p.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isEqualTo(true);
        assertThat(p.get(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(5_000);
        int linger = (int) p.get(ProducerConfig.LINGER_MS_CONFIG);
        int request = (int) p.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG);
        int delivery = (int) p.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG);
        assertThat(delivery).isGreaterThanOrEqualTo(linger + request);
    }
}

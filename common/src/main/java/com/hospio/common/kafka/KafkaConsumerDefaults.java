package com.hospio.common.kafka;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * The consumer settings every Hospio service shares. A malformed record is skipped instead of wedging
 * the partition; a handler that keeps failing is retried with exponential back-off (1s, 2s, 4s, 8s, 16s:
 * about half a minute) and then published to {@code <topic>.DLT} with the failure in its headers, so it can
 * be looked at and replayed instead of being lost; records that can never succeed (bad payload, failed
 * validation) go to the dead-letter topic at once. A consumer that stops polling is evicted by the broker
 * after five minutes rather than never.
 */
public final class KafkaConsumerDefaults {

    /** The only package Kafka payloads may deserialize into. */
    public static final String TRUSTED_PACKAGES = "com.hospio.common.kafka.events";

    public static final int MAX_POLL_RECORDS = 10;
    public static final int MAX_POLL_INTERVAL_MS = 300_000;
    public static final int SESSION_TIMEOUT_MS = 45_000;
    public static final int HEARTBEAT_INTERVAL_MS = 3_000;

    /** Suffix of the dead-letter topic a failed record is published to. */
    public static final String DLT_SUFFIX = ".DLT";
    public static final int RETRIES = 5;
    public static final long BACKOFF_INITIAL_MS = 1_000L;
    public static final long BACKOFF_MAX_MS = 30_000L;

    private KafkaConsumerDefaults() {
    }

    public static Map<String, Object> consumerConfig(String bootstrapServers, String autoOffsetReset) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, TRUSTED_PACKAGES);
        config.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, List.of(CooperativeStickyAssignor.class));
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_POLL_RECORDS);
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, MAX_POLL_INTERVAL_MS);
        config.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, SESSION_TIMEOUT_MS);
        config.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, HEARTBEAT_INTERVAL_MS);
        return config;
    }

    /** The factory without a dead-letter topic (failed records are logged and skipped): kept for callers built before the DLT. */
    public static ConcurrentKafkaListenerContainerFactory<String, Object> listenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(2_000L, 3)));
        return factory;
    }

    /**
     * The factory every service uses: {@code concurrency} consumer threads per listener (at most the topic's partitions do
     * work) and {@link #errorHandler} publishing records that keep failing to their dead-letter topic.
     */
    public static ConcurrentKafkaListenerContainerFactory<String, Object> listenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory, KafkaOperations<?, ?> deadLetters, int concurrency) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(Math.max(1, concurrency));
        factory.setCommonErrorHandler(errorHandler(deadLetters));
        return factory;
    }

    /**
     * Retries a failing record with exponential back-off, then publishes it to {@code <topic>.DLT} (any partition: the
     * dead-letter topics are smaller than their sources). Deserialization, conversion and validation failures are not
     * retried. If the dead-letter publish itself fails the record is retried again rather than lost.
     */
    public static DefaultErrorHandler errorHandler(KafkaOperations<?, ?> deadLetters) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(deadLetters,
                (record, ex) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(RETRIES);
        backOff.setInitialInterval(BACKOFF_INITIAL_MS);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(BACKOFF_MAX_MS);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // DeserializationException, MessageConversionException, ConversionException, MethodArgumentResolutionException,
        // NoSuchMethodException and ClassCastException are already fatal by default; failed validation is too.
        handler.addNotRetryableExceptions(
                org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException.class,
                com.hospio.common.exception.BadRequestException.class);
        return handler;
    }
}

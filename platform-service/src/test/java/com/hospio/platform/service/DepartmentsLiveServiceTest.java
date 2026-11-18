package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.OrderCreatedEvent;
import com.hospio.common.kafka.events.OrderSettledEvent;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

class DepartmentsLiveServiceTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final DepartmentsLiveService service = new DepartmentsLiveService(redis);

    DepartmentsLiveServiceTest() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForZSet()).thenReturn(zsets);
    }

    @Test
    void aBarOrderCountsForTheBarAndAKitchenOrderForTheKitchen() {
        service.bump(KafkaTopics.ORDER_CREATED, OrderCreatedEvent.builder().eventId("e1").hotelId("h1").outletId("o-bar")
                .outletKind("BAR").outletName("Bar").total(BigDecimal.TEN).build());
        verify(zsets).add(eq("hospio:live:h1:BAR"), eq("e1"), anyDouble());
        verify(values).set(eq("hospio:outlet-kind:o-bar"), eq("BAR"), eq(Duration.ofDays(90)));

        service.bump(KafkaTopics.ORDER_CREATED, OrderCreatedEvent.builder().eventId("e2").hotelId("h1").outletId("o-k")
                .outletKind("KITCHEN").outletName("Kitchen").total(BigDecimal.TEN).build());
        verify(zsets).add(eq("hospio:live:h1:KITCHEN"), eq("e2"), anyDouble());
    }

    @Test
    void aSettledOrderFindsItsOutletKindFromEarlierEvents() {
        when(values.get("hospio:outlet-kind:o-bar")).thenReturn("BAR");
        service.bump(KafkaTopics.ORDER_SETTLED, OrderSettledEvent.builder().eventId("e3").hotelId("h1").outletId("o-bar").orderNumber("B-0005").build());
        verify(zsets).add(eq("hospio:live:h1:BAR"), eq("e3"), anyDouble());
    }

    @Test
    void anUnknownOutletStaysWithTheKitchen() {
        when(values.get(anyString())).thenReturn(null);
        assertThat(service.outletKind(OrderSettledEvent.builder().outletId("o-x").build())).isNull();
        service.bump(KafkaTopics.ORDER_SETTLED, OrderSettledEvent.builder().eventId("e4").hotelId("h1").outletId("o-x").build());
        verify(zsets).add(eq("hospio:live:h1:KITCHEN"), eq("e4"), anyDouble());
    }
}

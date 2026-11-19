package com.hospio.platform.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.platform.client.IdentityClient;
import com.hospio.platform.repository.AlertRepository;
import com.hospio.platform.service.PlatformSettingsService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Every System health bean can be built by Spring from its constructors (a second constructor once stopped platform-service starting). */
class SystemHealthWiringTest {

    @Test
    void theSystemHealthBeansWire() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("t", Map.of("spring.kafka.bootstrap-servers", "localhost:9092",
                    "hospio.worker.enabled", "false")));
            ctx.registerBean(PropertySourcesPlaceholderConfigurer.class);
            ctx.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            ctx.registerBean(PlatformSettingsService.class, () -> mock(PlatformSettingsService.class));
            ctx.registerBean(AlertRepository.class, () -> mock(AlertRepository.class));
            ctx.registerBean(IdentityClient.class, () -> mock(IdentityClient.class));
            ctx.registerBean(EventPublisher.class, () -> mock(EventPublisher.class));
            ctx.registerBean(DeliveryService.class, () -> mock(DeliveryService.class));
            ctx.registerBean(com.hospio.platform.service.ActivityLogService.class, () -> mock(com.hospio.platform.service.ActivityLogService.class));
            ctx.scan("com.hospio.platform.system");
            ctx.refresh();
            assertThat(ctx.getBean(SystemHealthService.class)).isNotNull();
            assertThat(ctx.getBean(SystemAlertService.class)).isNotNull();
            assertThat(ctx.getBean(UnitProbe.class)).isNotNull();
        }
    }
}

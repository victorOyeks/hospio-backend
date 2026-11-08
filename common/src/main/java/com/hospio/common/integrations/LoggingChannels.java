package com.hospio.common.integrations;

import com.hospio.common.enums.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Development channels that only log. Real WhatsApp Business, SMS and email adapters replace these beans. */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "hospio.channels", name = "provider", havingValue = "logging", matchIfMissing = true)
public class LoggingChannels {

    @Bean
    public MessageChannel whatsAppChannel() {
        return channel(NotificationChannel.WHATSAPP);
    }

    @Bean
    public MessageChannel smsChannel() {
        return channel(NotificationChannel.SMS);
    }

    @Bean
    public MessageChannel emailChannel() {
        return channel(NotificationChannel.EMAIL);
    }

    private static MessageChannel channel(NotificationChannel kind) {
        return new MessageChannel() {
            @Override
            public NotificationChannel kind() {
                return kind;
            }

            @Override
            public String send(String to, String subject, String body) {
                log.info("[{}] to={} subject={} body={}", kind, to, subject, body);
                return kind.name().toLowerCase() + "-" + System.nanoTime();
            }
        };
    }
}
